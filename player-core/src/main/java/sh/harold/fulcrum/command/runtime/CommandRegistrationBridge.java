package sh.harold.fulcrum.command.runtime;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.command.Argument;
import sh.harold.fulcrum.command.ArgumentInjector;
import sh.harold.fulcrum.command.CommandDefinition;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.lang.reflect.Field;
import java.util.*;

public final class CommandRegistrationBridge {
    private CommandRegistrationBridge() {}

    public static void registerCommands(JavaPlugin plugin, Collection<CommandDefinition> definitions) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var def : definitions) {
                var builder = LiteralArgumentBuilder.<CommandSourceStack>literal(def.name())
                    .requires(src -> src.getSender().hasPermission("command." + def.name()));

                // Build argument chain from @Argument fields
                List<Field> argFields = new ArrayList<>();
                for (Field field : def.implementationClass().getFields()) {
                    if (field.isAnnotationPresent(Argument.class)) {
                        argFields.add(field);
                    }
                }

                ArgumentBuilder<CommandSourceStack, ?> argBuilder = builder;
                for (Field field : argFields) {
                    var arg = field.getAnnotation(Argument.class);
                    ArgumentType<?> type = switch (field.getType().getName()) {
                        case "java.lang.String" -> StringArgumentType.word();
                        case "int", "java.lang.Integer" -> IntegerArgumentType.integer();
                        case "org.bukkit.entity.Player" -> StringArgumentType.word(); // Player name, resolve later
                        default -> null;
                    };
                    if (type != null) {
                        argBuilder = ((ArgumentBuilder<CommandSourceStack, ?>) RequiredArgumentBuilder.argument(arg.value(), type));
                    }
                }

                argBuilder = argBuilder.executes(ctx -> {
                    try {
                        var instance = def.implementationClass().getDeclaredConstructor().newInstance();
                        // Inject arguments
                        ArgumentInjector.inject(new CommandContext(ctx.getSource().getSender()) {
                            @Override
                            public <T> T argument(String name, Class<T> type) {
                                Object value = null;
                                if (type == String.class) {
                                    value = ctx.getArgument(name, String.class);
                                } else if (type == Integer.class || type == int.class) {
                                    value = ctx.getArgument(name, Integer.class);
                                } else if (type == org.bukkit.entity.Player.class) {
                                    var playerName = ctx.getArgument(name, String.class);
                                    value = plugin.getServer().getPlayerExact(playerName);
                                }
                                return type.cast(value);
                            }
                        }, instance);
                        instance.execute(new CommandContext(ctx.getSource().getSender()));
                        return 1;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to execute command: " + e.getMessage());
                        return 0;
                    }
                });

                var rootNode = ((LiteralArgumentBuilder<CommandSourceStack>) argBuilder).build();
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
