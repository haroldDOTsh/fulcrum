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
import sh.harold.fulcrum.command.CommandExecutor;

public final class CommandRegistrationBridge {
    private CommandRegistrationBridge() {}

    public static void registerCommands(JavaPlugin plugin, Collection<?> definitions) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var def : definitions) {
                try {
                    // Use reflection to access CommandDefinition record components
                    var defClass = def.getClass();
                    String name = (String) defClass.getMethod("name").invoke(def);
                    String[] aliases = (String[]) defClass.getMethod("aliases").invoke(def);
                    Class<?> implClass = (Class<?>) defClass.getMethod("implementationClass").invoke(def);

                    var builder = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                        .requires(src -> src.getSender().hasPermission("command." + name));

                    // Build argument chain from @Argument fields
                    List<Field> argFields = new ArrayList<>();
                    for (Field field : implClass.getFields()) {
                        for (var ann : field.getAnnotations()) {
                            if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                                argFields.add(field);
                                break;
                            }
                        }
                    }

                    ArgumentBuilder<CommandSourceStack, ?> argBuilder = builder;
                    for (Field field : argFields) {
                        Object argAnn = null;
                        for (var ann : field.getAnnotations()) {
                            if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                                argAnn = ann;
                                break;
                            }
                        }
                        String argName = (String) argAnn.getClass().getMethod("value").invoke(argAnn);
                        ArgumentType<?> type = switch (field.getType().getName()) {
                            case "java.lang.String" -> StringArgumentType.word();
                            case "int", "java.lang.Integer" -> IntegerArgumentType.integer();
                            case "org.bukkit.entity.Player" -> StringArgumentType.word();
                            default -> null;
                        };
                        // Suggestion integration (skip for now, can be added reflectively if needed)
                        if (type != null) {
                            RequiredArgumentBuilder<CommandSourceStack, ?> reqArg = RequiredArgumentBuilder.argument(argName, type);
                            argBuilder = ((ArgumentBuilder<CommandSourceStack, ?>) reqArg);
                        }
                    }

                    argBuilder = argBuilder.executes(ctx -> {
                        try {
                            var instance = implClass.getDeclaredConstructor().newInstance();
                            // Inject arguments using a runtime CommandContext that delegates to Brigadier
                            var runtimeCtxCtor = Class.forName("sh.harold.fulcrum.command.CommandContext").getConstructor(Object.class);
                            Object runtimeCtx = runtimeCtxCtor.newInstance(ctx.getSource().getSender());
                            // ArgumentInjector.inject(runtimeCtx, instance); (skip for now, can be added reflectively)
                            var execMethod = implClass.getMethod("execute", runtimeCtx.getClass().getSuperclass());
                            execMethod.invoke(instance, runtimeCtx);
                            return 1;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to execute command: " + e.getMessage());
                            return 0;
                        }
                    });

                    var rootNode = ((LiteralArgumentBuilder<CommandSourceStack>) argBuilder).build();
                    event.registrar().register(rootNode);

                    for (String alias : aliases) {
                        event.registrar().register(
                            LiteralArgumentBuilder.<CommandSourceStack>literal(alias).redirect(rootNode).build()
                        );
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[command-core] Failed to register command: " + e.getMessage());
                }
            }
        });
    }
}
