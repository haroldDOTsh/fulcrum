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
import sh.harold.fulcrum.command.Suggestions;
import sh.harold.fulcrum.command.SuggestionResolver;
import sh.harold.fulcrum.command.SuggestionProviderAdapter;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import sh.harold.fulcrum.command.CommandContext;
import sh.harold.fulcrum.command.CommandExecutor;

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
                    // Suggestion integration
                    SuggestionProviderAdapter suggestionProvider = SuggestionResolver.resolve(field, def.implementationClass());
                    if (type != null) {
                        RequiredArgumentBuilder<CommandSourceStack, ?> reqArg = RequiredArgumentBuilder.argument(arg.value(), type);
                        if (suggestionProvider != null) {
                            reqArg.suggests((ctx, suggestionsBuilder) -> suggestionProvider.apply(ctx, suggestionsBuilder));
                        }
                        argBuilder = ((ArgumentBuilder<CommandSourceStack, ?>) reqArg);
                    }
                }

                argBuilder = argBuilder.executes(ctx -> {
                    try {
                        var instance = def.implementationClass().getDeclaredConstructor().newInstance();
                        // Inject arguments using a runtime CommandContext that delegates to Brigadier
                        sh.harold.fulcrum.command.CommandContext runtimeCtx = new sh.harold.fulcrum.command.CommandContext(ctx.getSource().getSender()) {
                            @SuppressWarnings("unchecked")
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
                                return (T) value;
                            }
                        };
                        ArgumentInjector.inject(runtimeCtx, instance);
                        ((sh.harold.fulcrum.command.CommandExecutor) instance).execute(runtimeCtx);
                        return 1;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to execute command: " + e.getMessage());
                        return 0;
                    }
                });

                var rootNode = ((LiteralArgumentBuilder<CommandSourceStack>) argBuilder).build();
                event.registrar().register(rootNode);

                for (String alias : def.aliases()) {
                    event.registrar().register(
                        LiteralArgumentBuilder.<CommandSourceStack>literal(alias).redirect(rootNode).build()
                    );
                }
            }
        });
    }
}
