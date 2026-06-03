package xyz.froquefy.playercount;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodically records the proxy's live player counts — network total and
 * per-backend breakdown — into MySQL for an external consumer such as a website.
 *
 * <p>The counts come straight from the proxy's in-memory state (the same data
 * {@code /glist} reports), so no backend-server plugins are required.
 */
@Plugin(
        id = "playercount",
        name = "PlayerCount",
        version = "1.0.0",
        description = "Records live network and per-server player counts to MySQL for website use.",
        authors = {"froquefy"}
)
public final class PlayerCountPlugin {

    /** Grace period before the first write, so backends have a moment to register on boot. */
    private static final Duration STARTUP_DELAY = Duration.ofSeconds(5);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private Database database;
    private ScheduledTask task;

    @Inject
    public PlayerCountPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PluginConfig config;
        try {
            config = PluginConfig.load(dataDirectory);
        } catch (RuntimeException e) {
            logger.error("Could not read config.properties - PlayerCount is disabled for this session. "
                    + "Fix the file and restart the proxy.", e);
            return;
        }

        // Lazy pool: a down MySQL here can't block or fail startup (see Database).
        this.database = new Database(config, logger);

        this.task = server.getScheduler().buildTask(this, this::recordSnapshot)
                .delay(STARTUP_DELAY)
                .repeat(Duration.ofSeconds(config.intervalSeconds()))
                .schedule();

        logger.info("PlayerCount enabled - recording counts every {}s into tables {}network and {}servers.",
                config.intervalSeconds(), config.tablePrefix(), config.tablePrefix());
    }

    private void recordSnapshot() {
        int total = server.getPlayerCount();
        Map<String, Integer> perServer = new LinkedHashMap<>();
        for (RegisteredServer registered : server.getAllServers()) {
            perServer.put(registered.getServerInfo().getName(), registered.getPlayersConnected().size());
        }
        database.writeSnapshot(total, perServer);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (task != null) {
            task.cancel();
        }
        if (database != null) {
            database.close();
        }
        logger.info("PlayerCount disabled.");
    }
}
