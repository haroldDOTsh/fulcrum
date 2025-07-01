package sh.harold.fulcrum.command.runtime;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.audience.Audience;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.GenericResponse;
import sh.harold.fulcrum.api.message.Message;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public final class CommandRegistrationBridge {
    // Per-player cooldowns: UUID -> (Command class -> Instant)
    private static final Map<UUID, Map<Class<?>, Instant>> cooldowns = new ConcurrentHashMap<>();
    // Bypass set (UUIDs)
    private static final Set<UUID> bypassCooldown = ConcurrentHashMap.newKeySet();
    private CommandRegistrationBridge() {
    }

    public static void registerCommands(JavaPlugin plugin, Collection<?> definitions, Commands registrar) {
        for (var def : definitions) {
            plugin.getLogger().info("[command-core] Entering registration for: " + def);
            try {
                // Use reflection to access CommandDefinition record components
                var defClass = def.getClass();
                plugin.getLogger().info("[command-core] defClass: " + defClass.getName());
                String name = (String) defClass.getMethod("name").invoke(def);
                String[] aliases = (String[]) defClass.getMethod("aliases").invoke(def);
                Class<?> implClass = (Class<?>) defClass.getMethod("implementationClass").invoke(def);
                plugin.getLogger().info("[command-core] name: " + name + ", aliases: " + java.util.Arrays.toString(aliases) + ", implClass: " + implClass.getName());

                // --- Annotation inspection (reflection only, no direct references) ---
                boolean forceSync = false;
                boolean forceAsync = false;
                int cooldownSeconds = 0;
                String execTypeName = "ALL";
                for (var ann : implClass.getAnnotations()) {
                    String annName = ann.annotationType().getName();
                    switch (annName) {
                        case "sh.harold.fulcrum.command.annotations.Sync" -> forceSync = true;
                        case "sh.harold.fulcrum.command.annotations.Async" -> forceAsync = true;
                        case "sh.harold.fulcrum.command.annotations.Cooldown" -> {
                            try {
                                cooldownSeconds = (int) ann.annotationType().getMethod("seconds").invoke(ann);
                            } catch (Exception ignored) {
                            }
                        }
                        case "sh.harold.fulcrum.command.annotations.Executor" -> {
                            try {
                                Object val = ann.annotationType().getMethod("value").invoke(ann);
                                execTypeName = val.toString();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                if (!forceSync && !forceAsync) forceAsync = true;
                plugin.getLogger().info("[command-core] execType: " + execTypeName + ", cooldownSeconds: " + cooldownSeconds + ", forceSync: " + forceSync + ", forceAsync: " + forceAsync);

                // Build argument chain from @Argument fields
                List<Field> argFields = new ArrayList<>();
                for (Field field : implClass.getDeclaredFields()) {
                    plugin.getLogger().info("[command-core] Inspecting field: " + field.getName() + " Annotations: " + java.util.Arrays.toString(field.getAnnotations()));
                    for (var ann : field.getAnnotations()) {
                        plugin.getLogger().info("[command-core] Field annotation: " + ann.annotationType().getName());
                        if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                            argFields.add(field);
                            break;
                        }
                    }
                }
                plugin.getLogger().info("[command-core] argFields: " + argFields.size());

                // Sanity check logging
                plugin.getLogger().info("[command-core] Sanity check: Registering command class: " + implClass.getName());
                for (var field : implClass.getDeclaredFields()) {
                    plugin.getLogger().info("[command-core] Field: " + field.getName() + " Type: " + field.getType().getName() + " Annotations: " + java.util.Arrays.toString(field.getAnnotations()));
                }

                // --- CORRECTED ARGUMENT CHAIN CONSTRUCTION (REVERSE NESTING, PAPER-ONLY) ---
                ArgumentBuilder<CommandSourceStack, ?> argChain = null;
                Class<?> commandContextClass;
                java.lang.reflect.Constructor<?> runtimeCtxCtor;
                java.lang.reflect.Constructor<?> implCtor;
                java.lang.reflect.Method execMethod;
                try {
                    commandContextClass = Class.forName("sh.harold.fulcrum.command.CommandContext");
                    runtimeCtxCtor = commandContextClass.getConstructor(CommandSourceStack.class);
                    implCtor = implClass.getDeclaredConstructor();
                    execMethod = implClass.getMethod("execute", commandContextClass);
                    plugin.getLogger().info("[command-core] Reflection success: " + implClass.getName() + "#execute(" + commandContextClass.getSimpleName() + ")");
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "[command-core] Reflection setup failed for command: " + implClass.getName(), e);
                    continue;
                }
                for (int i = argFields.size() - 1; i >= 0; i--) {
                    final Field field = argFields.get(i);
                    Object argAnn = null;
                    for (var ann : field.getAnnotations()) {
                        if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                            argAnn = ann;
                            break;
                        }
                    }
                    if (argAnn == null) {
                        plugin.getLogger().warning("[command-core] Argument annotation missing for field: " + field.getName());
                        continue;
                    }
                    final String argName = (String) argAnn.getClass().getMethod("value").invoke(argAnn);
                    plugin.getLogger().info("[command-core] Registering argument: " + argName + " for field: " + field.getName());
                    final ArgumentType<?> type = switch (field.getType().getName()) {
                        case "java.lang.String" -> StringArgumentType.word();
                        case "int", "java.lang.Integer" -> IntegerArgumentType.integer();
                        // Remove Bukkit Player reference; use string for player names
                        default -> null;
                    };
                    if (type == null) {
                        plugin.getLogger().warning("[command-core] Unsupported argument type for field: " + field.getName() + " (" + field.getType().getName() + ")");
                        continue;
                    }
                    RequiredArgumentBuilder<CommandSourceStack, ?> reqArg = RequiredArgumentBuilder.argument(argName, type);
                    if (i == argFields.size() - 1) {
                        // Attach executes to the last argument node
                        final int finalCooldownSeconds = cooldownSeconds;
                        final String finalExecTypeName = execTypeName;
                        final Class<?> finalImplClass = implClass;
                        final boolean finalForceSync = forceSync;
                        final java.lang.reflect.Constructor<?> finalImplCtor = implCtor;
                        final java.lang.reflect.Constructor<?> finalRuntimeCtxCtor = runtimeCtxCtor;
                        final java.lang.reflect.Method finalExecMethod = execMethod;
                        reqArg = reqArg.executes(ctx -> {
                            CommandSourceStack sourceStack = ctx.getSource();
                            var sender = sourceStack.getSender();
                            Audience audience = sender instanceof Audience ? (Audience) sender : null;
                            // Executor type enforcement (error message)
                            boolean valid = switch (finalExecTypeName) {
                                case "PLAYER" -> sender instanceof Player;
                                case "CONSOLE" -> sender instanceof ConsoleCommandSender;
                                default -> true;
                            };
                            if (!valid) {
                                Message.error("internal.command.executor.invalid").send(audience);
                                return 0;
                            }
                            // Cooldown enforcement (players only)
                            Player player = sender instanceof Player p ? p : null;
                            if (finalCooldownSeconds > 0 && player != null) {
                                if (!bypassCooldown.contains(player.getUniqueId())) {
                                    var now = Instant.now();
                                    var map = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
                                    var last = map.getOrDefault(finalImplClass, Instant.EPOCH);
                                    if (now.isBefore(last.plusSeconds(finalCooldownSeconds))) {
                                        long left = last.plusSeconds(finalCooldownSeconds).getEpochSecond() - now.getEpochSecond();
                                        Message.error(GenericResponse.ERROR_COOLDOWN).send(audience);
                                        return 0;
                                    }
                                    map.put(finalImplClass, now);
                                }
                            }
                            // TODO: Permission check via @Rank or similar
                            Runnable exec = () -> {
                                try {
                                    var instance = finalImplCtor.newInstance();
                                    // Inject parsed arguments into @Argument fields
                                    for (Field argField : finalImplClass.getDeclaredFields()) {
                                        for (var ann : argField.getAnnotations()) {
                                            if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                                                String argFieldName = (String) ann.getClass().getMethod("value").invoke(ann);
                                                Object value = null;
                                                try {
                                                    value = ctx.getArgument(argFieldName, argField.getType());
                                                } catch (Exception ex) {
                                                    plugin.getLogger().warning("[command-core] Could not get argument '" + argFieldName + "' for field '" + argField.getName() + "': " + ex);
                                                }
                                                argField.setAccessible(true);
                                                argField.set(instance, value);
                                            }
                                        }
                                    }
                                    Object runtimeCtx = finalRuntimeCtxCtor.newInstance(sourceStack);
                                    finalExecMethod.invoke(instance, runtimeCtx);
                                } catch (Exception e) {
                                    plugin.getLogger().log(java.util.logging.Level.WARNING, "[command-core] Command execution failed for " + finalImplClass.getName(), e);
                                }
                            };
                            if (finalForceSync) {
                                plugin.getServer().getScheduler().runTask(plugin, exec);
                            } else {
                                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, exec);
                            }
                            return 1;
                        });
                    }
                    if (argChain != null) {
                        reqArg.then(argChain);
                    }
                    argChain = reqArg;
                }

                final int finalCooldownSeconds = cooldownSeconds;
                final String finalExecTypeName = execTypeName;
                final Class<?> finalImplClass = implClass;
                final boolean finalForceSync = forceSync;
                final java.lang.reflect.Constructor<?> finalImplCtor = implCtor;
                final java.lang.reflect.Constructor<?> finalRuntimeCtxCtor = runtimeCtxCtor;
                final java.lang.reflect.Method finalExecMethod = execMethod;
                LiteralArgumentBuilder<CommandSourceStack> rootBuilder = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                        .requires(src -> {
                            var sender = src.getSender();
                            if (!sender.hasPermission("command." + name)) return false;
                            return switch (finalExecTypeName) {
                                case "PLAYER" -> sender instanceof Player;
                                case "CONSOLE" -> sender instanceof ConsoleCommandSender;
                                default -> true;
                            };
                        });
                if (argChain != null) {
                    rootBuilder.then(argChain);
                } else {
                    rootBuilder = rootBuilder.executes(ctx -> {
                        CommandSourceStack sourceStack = ctx.getSource();
                        var sender = sourceStack.getSender();
                        Audience audience = sender instanceof Audience ? (Audience) sender : null;
                        boolean valid = switch (finalExecTypeName) {
                            case "PLAYER" -> sender instanceof Player;
                            case "CONSOLE" -> sender instanceof ConsoleCommandSender;
                            default -> true;
                        };
                        if (!valid) {
                            Message.error("internal.command.executor.invalid").send(audience);
                            return 0;
                        }
                        Player player = sender instanceof Player p ? p : null;
                        if (finalCooldownSeconds > 0 && player != null) {
                            if (!bypassCooldown.contains(player.getUniqueId())) {
                                var now = Instant.now();
                                var map = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
                                var last = map.getOrDefault(finalImplClass, Instant.EPOCH);
                                if (now.isBefore(last.plusSeconds(finalCooldownSeconds))) {
                                    long left = last.plusSeconds(finalCooldownSeconds).getEpochSecond() - now.getEpochSecond();
                                    Message.error(GenericResponse.ERROR_COOLDOWN).send(audience);
                                    return 0;
                                }
                                map.put(finalImplClass, now);
                            }
                        }
                        Runnable exec = () -> {
                            try {
                                var instance = finalImplCtor.newInstance();
                                // Inject parsed arguments into @Argument fields
                                for (Field argField : finalImplClass.getDeclaredFields()) {
                                    for (var ann : argField.getAnnotations()) {
                                        if (ann.annotationType().getName().equals("sh.harold.fulcrum.command.annotations.Argument")) {
                                            String argFieldName = (String) ann.getClass().getMethod("value").invoke(ann);
                                            Object value = null;
                                            try {
                                                value = ctx.getArgument(argFieldName, argField.getType());
                                            } catch (Exception ex) {
                                                plugin.getLogger().warning("[command-core] Could not get argument '" + argFieldName + "' for field '" + argField.getName() + "': " + ex);
                                            }
                                            argField.setAccessible(true);
                                            argField.set(instance, value);
                                        }
                                    }
                                }
                                Object runtimeCtx = finalRuntimeCtxCtor.newInstance(sourceStack);
                                finalExecMethod.invoke(instance, runtimeCtx);
                            } catch (Exception e) {
                                plugin.getLogger().log(java.util.logging.Level.WARNING, "[command-core] Command execution failed for " + finalImplClass.getName(), e);
                            }
                        };
                        if (finalForceSync) {
                            plugin.getServer().getScheduler().runTask(plugin, exec);
                        } else {
                            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, exec);
                        }
                        return 1;
                    });
                }

                // Register the root node (always a LiteralArgumentBuilder)
                var rootNode = rootBuilder.build();
                registrar.register(rootNode);

                for (String alias : aliases) {
                    registrar.register(
                            LiteralArgumentBuilder.<CommandSourceStack>literal(alias).redirect(rootNode).build()
                    );
                }
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[command-core] Failed to register command", e);
            }
        }
    }

    // Deprecated: use the new method with registrar
    @Deprecated
    public static void registerCommands(JavaPlugin plugin, Collection<?> definitions) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            registerCommands(plugin, definitions, event.registrar());
        });
    }

    // Command to bypass cooldowns (add/remove self)
    public static boolean toggleBypassCooldown(Player player) {
        var uuid = player.getUniqueId();
        if (bypassCooldown.contains(uuid)) {
            bypassCooldown.remove(uuid);
            Message.info("command.bypass.disabled").send(player);
            return false;
        } else {
            bypassCooldown.add(uuid);
            Message.info("command.bypass.enabled").send(player);
            return true;
        }
    }
}
