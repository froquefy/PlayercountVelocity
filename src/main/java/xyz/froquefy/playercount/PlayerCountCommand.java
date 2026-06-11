package xyz.froquefy.playercount;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/**
 * {@code /playercount reload} — re-reads {@code config.properties} and re-applies it
 * (reconnecting MySQL and rescheduling the reconcile) without a proxy restart.
 *
 * <p>Gated on the {@code playercount.reload} permission; the console always passes.
 */
final class PlayerCountCommand implements SimpleCommand {

    private final PlayerCountPlugin plugin;

    PlayerCountCommand(PlayerCountPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (plugin.start()) {
                source.sendMessage(Component.text("PlayerCount: config reloaded.", NamedTextColor.GREEN));
            } else {
                source.sendMessage(Component.text(
                        "PlayerCount: reload failed - see the proxy console. The previous config is still active.",
                        NamedTextColor.RED));
            }
            return;
        }

        source.sendMessage(Component.text("Usage: /playercount reload", NamedTextColor.YELLOW));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("playercount.reload");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return invocation.arguments().length == 0 ? List.of("reload") : List.of();
    }
}
