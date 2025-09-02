package sh.harold.fulcrum.lifecycle;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CommandRegistrar {
    private static final Logger LOGGER = Logger.getLogger(CommandRegistrar.class.getName());
    private static final List<LiteralCommandNode<CommandSourceStack>> pending = new ArrayList<>();
    private static boolean registered = false;

    private CommandRegistrar() {
    }

    public static void register(LiteralCommandNode<CommandSourceStack> command) {
        pending.add(command);
    }

    public static void hook(JavaPlugin plugin) {
        if (registered) {
            LOGGER.warning("CommandRegistrar.hook() called but already registered; ignoring.");
            return;
        }
        registered = true;

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var command : pending) {
                try {
                    event.registrar().register(command);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to register command: /" + command.getLiteral(), e);
                }
            }
        });
    }
}
