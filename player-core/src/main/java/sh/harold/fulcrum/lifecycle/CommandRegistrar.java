package sh.harold.fulcrum.lifecycle;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class CommandRegistrar {

    private static final List<LiteralCommandNode<CommandSourceStack>> pending = new ArrayList<>();
    private static boolean registered = false;

    private CommandRegistrar() {}

    public static void register(LiteralCommandNode<CommandSourceStack> command) {
        pending.add(command);
    }

    public static void hook(JavaPlugin plugin) {
        if (registered) return;
        registered = true;

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var command : pending) {
                event.registrar().register(command);
            }
        });
    }
}
