package xyz.froquefy.playercount;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maintains a MySQL table of the players currently online across the network, for an
 * external consumer such as a website to read.
 *
 * <p>Driven entirely by proxy-side events — {@link ServerConnectedEvent} adds or moves
 * a player, {@link DisconnectEvent} removes one — so the table always holds exactly the
 * online players and <strong>no backend (Paper/Spigot) plugin is required</strong>. A
 * periodic full reconcile against the proxy's own player list ({@code /glist}'s data)
 * repairs any drift and clears phantom rows left by an unclean shutdown.
 *
 * <p>{@code /playercount reload} re-reads the config and re-applies it without a proxy
 * restart.
 */
@Plugin(
        id = "playercount",
        name = "PlayerCount",
        version = "2.0.0",
        description = "Records the live online player list (nick + backend server) to MySQL for website use.",
        authors = {"froquefy"}
)
public final class PlayerCountPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    /** Guards the {@link #database}/{@link #reconcileTask} swap done by start/reload/shutdown. */
    private final Object lifecycleLock = new Object();
    private volatile Database database;
    private ScheduledTask reconcileTask;

    @Inject
    public PlayerCountPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        registerCommand();
        start();
    }

    private void registerCommand() {
        CommandManager commands = server.getCommandManager();
        CommandMeta meta = commands.metaBuilder("playercount").plugin(this).build();
        commands.register(meta, new PlayerCountCommand(this));
    }

    /**
     * (Re)initialises the database and reconcile task from the on-disk config. Used for
     * both the initial enable and {@code /playercount reload}. On a config-read failure
     * the previous state (if any) is left running untouched.
     *
     * @return {@code true} if the config loaded and the plugin is now (re)started
     */
    boolean start() {
        synchronized (lifecycleLock) {
            PluginConfig config;
            try {
                config = PluginConfig.load(dataDirectory);
            } catch (RuntimeException e) {
                logger.error("Could not read config.properties. {}", database == null
                        ? "PlayerCount is disabled for this session - fix the file and restart the proxy."
                        : "Keeping the previously loaded config; fix the file and reload again.", e);
                return false;
            }

            stopInternal();

            this.database = new Database(config, logger);
            // Immediate reconcile: clears phantom rows after a crash on boot, and
            // repopulates the table on reload (no events fire for already-connected players).
            reconcile();
            this.reconcileTask = server.getScheduler().buildTask(this, this::reconcile)
                    .delay(Duration.ofSeconds(config.intervalSeconds()))
                    .repeat(Duration.ofSeconds(config.intervalSeconds()))
                    .schedule();

            logger.info("PlayerCount enabled - tracking the online player list in table {}players, "
                    + "reconciling every {}s.", config.tablePrefix(), config.intervalSeconds());
            return true;
        }
    }

    /** Cancels the reconcile task and closes the database, if either is live. */
    private void stopInternal() {
        if (reconcileTask != null) {
            reconcileTask.cancel();
            reconcileTask = null;
        }
        Database current = this.database;
        if (current != null) {
            current.close();
            this.database = null;
        }
    }

    /** Rebuilds the table to match the proxy's current online players. */
    private void reconcile() {
        Database db = this.database;
        if (db == null) {
            return;
        }
        Map<String, String> online = new LinkedHashMap<>();
        for (Player player : server.getAllPlayers()) {
            player.getCurrentServer().ifPresent(connection ->
                    online.put(player.getUsername(), connection.getServerInfo().getName()));
        }
        db.reconcile(online);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Database db = this.database;
        if (db != null) {
            db.upsert(event.getPlayer().getUsername(), event.getServer().getServerInfo().getName());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Database db = this.database;
        if (db != null) {
            db.remove(event.getPlayer().getUsername());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        synchronized (lifecycleLock) {
            Database db = this.database;
            if (db != null) {
                // Everyone goes offline with the proxy: empty the table so the website
                // never shows phantom online players while the proxy is down.
                db.reconcile(Map.of());
            }
            stopInternal();
        }
        logger.info("PlayerCount disabled.");
    }
}
