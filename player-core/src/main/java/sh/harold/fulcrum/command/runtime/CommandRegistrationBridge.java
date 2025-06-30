package sh.harold.fulcrum.command.runtime;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.command.CommandDefinition;
import sh.harold.fulcrum.command.CommandExecutor;
import sh.harold.fulcrum.command.CommandContext;

import java.util.Collection;

public final class CommandRegistrationBridge {
    private CommandRegistrationBridge() {}

    public static void registerCommands(JavaPlugin plugin, Collection<CommandDefinition> definitions) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var def : definitions) {
                LiteralCommandNode<CommandSourceStack> rootNode = LiteralArgumentBuilder
                    .<CommandSourceStack>literal(def.name())
                    .requires(src -> src.getSender().hasPermission("command." + def.name())) // vanilla-compatible permission check
                    .executes(ctx -> {
                        try {
                            var instance = def.implementationClass().getDeclaredConstructor().newInstance();
                            // Use getSender() for vanilla parity (Audience, not just Bukkit)
                            instance.execute(new CommandContext(ctx.getSource().getSender()));
                            return 1;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to execute command: " + e.getMessage());
                            return 0;
                        }
                    })
                    .build();

                event.registrar().register(rootNode);

                for (var alias : def.aliases()) {
                    event.registrar().register(
                        LiteralArgumentBuilder.<CommandSourceStack>literal(alias).redirect(rootNode).build()
                    );
                }
            }
        });
    }
}
