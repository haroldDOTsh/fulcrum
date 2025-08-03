package sh.harold.fulcrum.module.commands;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sh.harold.fulcrum.environment.SimpleEnvironmentDetector;
import sh.harold.fulcrum.module.ModuleManager;
import sh.harold.fulcrum.module.ModuleMetadata;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class ModuleListCommand {

    private final ModuleManager moduleManager;

    public ModuleListCommand(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("runtimeinfo")
                .requires(source -> source.getSender().hasPermission("fulcrum.modules.list"))
                .executes(ctx -> {
                    // Default command shows runtime overview
                    CommandSourceStack source = ctx.getSource();
                    var sender = source.getSender();
                    
                    String environment = moduleManager.getEnvironmentDetector().getCurrentEnvironment();
                    int moduleCount = moduleManager.getLoadedModules().size();
                    
                    sender.sendMessage(Component.text("=== Fulcrum Runtime Info ===", NamedTextColor.GOLD));
                    sender.sendMessage(Component.text("Environment: ", NamedTextColor.GRAY)
                            .append(Component.text(environment, NamedTextColor.GREEN)));
                    sender.sendMessage(Component.text("Loaded Modules: ", NamedTextColor.GRAY)
                            .append(Component.text(moduleCount, NamedTextColor.GREEN)));
                    sender.sendMessage(Component.text("Use /runtimeinfo list for module details", NamedTextColor.DARK_GRAY));
                    return 1;
                })
                .then(literal("list")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var sender = source.getSender();

                            // Check if moduleManager is null
                            if (moduleManager == null) {
                                sender.sendMessage(Component.text("Module system is not initialized.", NamedTextColor.RED));
                                return 1;
                            }

                            sender.sendMessage(Component.text("Loaded Fulcrum Modules:", NamedTextColor.GOLD));
                            sender.sendMessage(Component.text("Current Environment: ", NamedTextColor.GRAY)
                                    .append(Component.text(moduleManager.getEnvironmentDetector().getCurrentEnvironment(), NamedTextColor.GREEN)));

                            var loadedModules = moduleManager.getLoadedModules();
                            if (loadedModules == null || loadedModules.isEmpty()) {
                                sender.sendMessage(Component.text("No modules are currently loaded.", NamedTextColor.YELLOW));
                                return 1;
                            }

                            for (ModuleMetadata metadata : loadedModules) {
                                // Add null checks for safety
                                if (metadata == null) {
                                    continue;
                                }

                                String name = metadata.name();
                                String description = metadata.description();

                                // Handle null values gracefully
                                if (name == null) {
                                    name = "Unknown Module";
                                }
                                if (description == null) {
                                    description = "No description available";
                                }

                                Component line = Component.text("• ", NamedTextColor.GRAY)
                                        .append(Component.text(name, NamedTextColor.GREEN))
                                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                                        .append(Component.text(description, NamedTextColor.WHITE));
                                sender.sendMessage(line);
                            }

                            return 1;
                        }))
                .then(literal("environment")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var sender = source.getSender();
                            
                            SimpleEnvironmentDetector detector = moduleManager.getEnvironmentDetector();
                            String environment = detector.getCurrentEnvironment();
                            
                            sender.sendMessage(Component.text("=== Environment Info ===", NamedTextColor.GOLD));
                            sender.sendMessage(Component.text("Current Environment: ", NamedTextColor.GRAY)
                                    .append(Component.text(environment, NamedTextColor.GREEN)));
                            sender.sendMessage(Component.text("Config Location: ", NamedTextColor.GRAY)
                                    .append(Component.text("./ENVIRONMENT", NamedTextColor.AQUA)));
                            sender.sendMessage(Component.text("To change: Edit ENVIRONMENT file and use /runtimeinfo reload", NamedTextColor.DARK_GRAY));
                            
                            return 1;
                        }))
                .then(literal("reload")
                        .requires(source -> source.getSender().hasPermission("fulcrum.admin"))
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var sender = source.getSender();
                            
                            String oldEnv = moduleManager.getEnvironmentDetector().getCurrentEnvironment();
                            moduleManager.getEnvironmentDetector().reload();
                            String newEnv = moduleManager.getEnvironmentDetector().getCurrentEnvironment();
                            
                            if (oldEnv.equals(newEnv)) {
                                sender.sendMessage(Component.text("Environment unchanged: " + newEnv, NamedTextColor.YELLOW));
                            } else {
                                sender.sendMessage(Component.text("Environment changed: ", NamedTextColor.GREEN)
                                        .append(Component.text(oldEnv, NamedTextColor.RED))
                                        .append(Component.text(" → ", NamedTextColor.GRAY))
                                        .append(Component.text(newEnv, NamedTextColor.GREEN)));
                                sender.sendMessage(Component.text("⚠ Server restart required for module changes", NamedTextColor.YELLOW));
                            }
                            
                            return 1;
                        }))
                .build();
    }
}
