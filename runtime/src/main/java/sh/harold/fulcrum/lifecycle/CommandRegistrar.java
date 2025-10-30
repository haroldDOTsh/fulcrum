package sh.harold.fulcrum.lifecycle;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CommandRegistrar {
    private static final Logger LOGGER = Logger.getLogger(CommandRegistrar.class.getName());
    private static final List<LiteralCommandNode<CommandSourceStack>> pending = new ArrayList<>();
    private static final List<Consumer<Commands>> mutators = new ArrayList<>();
    private static boolean registered = false;

    private CommandRegistrar() {
    }

    public static void register(LiteralCommandNode<CommandSourceStack> command) {
        pending.add(command);
    }

    public static void mutate(Consumer<Commands> mutator) {
        mutators.add(mutator);
    }

    public static void registerAlias(LiteralCommandNode<CommandSourceStack> target, String alias) {
        if (target == null) {
            throw new IllegalArgumentException("Target command node cannot be null");
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Alias literal cannot be null or blank");
        }

        var builder = Commands.literal(alias);
        var requirement = target.getRequirement();
        if (requirement != null) {
            builder.requires(requirement);
        }

        var command = target.getCommand();
        if (command != null) {
            builder.executes(command);
        }

        builder.redirect(target);
        register(builder.build());
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
            for (var mutator : mutators) {
                try {
                    mutator.accept(event.registrar());
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to apply command mutator", e);
                }
            }
        });
    }

    public static void reset() {
        pending.clear();
        mutators.clear();
        registered = false;
    }
}
