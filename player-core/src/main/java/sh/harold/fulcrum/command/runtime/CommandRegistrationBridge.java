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
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.GenericResponse;
import sh.harold.fulcrum.api.message.Message;
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
import sh.harold.fulcrum.command.Async;
import sh.harold.fulcrum.command.Sync;
import sh.harold.fulcrum.command.Cooldown;
import sh.harold.fulcrum.command.Executor;
import sh.harold.fulcrum.command.CommandExecutorType;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;


public final class CommandRegistrationBridge {
    private CommandRegistrationBridge() {}

    // Per-player cooldowns: UUID -> (Command class -> Instant)
    private static final Map<UUID, Map<Class<?>, Instant>> cooldowns = new ConcurrentHashMap<>();
    // Bypass set (UUIDs)
    private static final Set<UUID> bypassCooldown = ConcurrentHashMap.newKeySet();

    public static void registerCommands(JavaPlugin plugin, Collection<?> definitions) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var def : definitions) {
                try {
                    // Use reflection to access CommandDefinition record components
                    var defClass = def.getClass();
                    String name = (String) defClass.getMethod("name").invoke(def);
                    String[] aliases = (String[]) defClass.getMethod("aliases").invoke(def);
                    Class<?> implClass = (Class<?>) defClass.getMethod("implementationClass").invoke(def);

                    // --- Annotation inspection ---
                    boolean forceSync = implClass.isAnnotationPresent(Sync.class);
                    boolean forceAsync = implClass.isAnnotationPresent(Async.class) || !forceSync;
                    Cooldown cooldownAnn = implClass.getAnnotation(Cooldown.class);
                    int cooldownSeconds = cooldownAnn != null ? cooldownAnn.seconds() : 0;
                    Executor executorAnn = implClass.getAnnotation(Executor.class);
                    CommandExecutorType execType = executorAnn != null ? executorAnn.value() : CommandExecutorType.ALL;

                    var builder = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                        .requires(src -> {
                            // Permission check
                            if (!src.getSender().hasPermission("command." + name)) return false;
                            // Executor type check
                            return switch (execType) {
                                case PLAYER -> src.getSender() instanceof Player;
                                case CONSOLE -> src.getSender() instanceof ConsoleCommandSender;
                                case ALL -> true;
                            };
                        });

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
                            argBuilder = reqArg;
                        }
                    }

                    argBuilder = argBuilder.executes(ctx -> {
                        var sender = ctx.getSource().getSender();
                        // Executor type enforcement (error message)
                        switch (execType) {
                            case PLAYER -> {
                                if (!(sender instanceof Player p)) {
                                    Message.error("internal.command.executor.invalid").send(sender);
                                    return 0;
                                }
                            }
                            case CONSOLE -> {
                                if (!(sender instanceof ConsoleCommandSender)) {
                                    Message.error("internal.command.executor.invalid").send(sender);
                                    return 0;
                                }
                            }
                            case ALL -> {}
                        }
                        // Cooldown enforcement (players only)
                        if (cooldownSeconds > 0 && sender instanceof Player p) {
                            if (!bypassCooldown.contains(p.getUniqueId())) {
                                var now = Instant.now();
                                var map = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
                                var last = map.getOrDefault(implClass, Instant.EPOCH);
                                if (now.isBefore(last.plusSeconds(cooldownSeconds))) {
                                    long left = last.plusSeconds(cooldownSeconds).getEpochSecond() - now.getEpochSecond();
                                    Message.error(GenericResponse.ERROR_COOLDOWN).send(sender);
                                    return 0;
                                }
                                map.put(implClass, now);
                            }
                        }
                        // TODO: Permission check via @Rank or similar
                        // Example:
                        // if (!hasRequiredRank(sender, ...)) {
                        //     Message.error(GenericResponse.ERROR_NO_PERMISSION).send(sender);
                        //     return 0;
                        // }
                        Runnable exec = () -> {
                            try {
                                var instance = implClass.getDeclaredConstructor().newInstance();
                                var runtimeCtxCtor = Class.forName("sh.harold.fulcrum.command.CommandContext").getConstructor(Object.class);
                                Object runtimeCtx = runtimeCtxCtor.newInstance(sender);
                                var execMethod = implClass.getMethod("execute", runtimeCtx.getClass().getSuperclass());
                                execMethod.invoke(instance, runtimeCtx);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to execute command: " + e.getMessage());
                            }
                        };
                        if (forceSync) {
                            plugin.getServer().getScheduler().runTask(plugin, exec);
                        } else {
                            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, exec);
                        }
                        return 1;
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

    // Command to bypass cooldowns (add/remove self)
    public static boolean toggleBypassCooldown(Player player) {
        var uuid = player.getUniqueId();
        if (bypassCooldown.contains(uuid)) {
            bypassCooldown.remove(uuid);
            player.sendMessage("§aCooldown bypass disabled.");
            return false;
        } else {
            bypassCooldown.add(uuid);
            player.sendMessage("§aCooldown bypass enabled.");
            return true;
        }
    }
}
