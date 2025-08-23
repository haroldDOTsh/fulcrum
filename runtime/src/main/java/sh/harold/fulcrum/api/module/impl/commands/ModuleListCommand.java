package sh.harold.fulcrum.api.module.impl.commands;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleMetadata;

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
                    
                    String environment = FulcrumEnvironment.getCurrent();
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
                                    .append(Component.text(FulcrumEnvironment.getCurrent(), NamedTextColor.GREEN)));

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
                            
                            String environment = FulcrumEnvironment.getCurrent();
                            
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
                            
                            String oldEnv = FulcrumEnvironment.getCurrent();
                            // Note: Environment reload functionality needs to be implemented through FulcrumBootstrapper
                            // For now, we'll just show a message indicating reload is not available via this command
                            sender.sendMessage(Component.text("⚠ Environment reload via command is not yet implemented in the new bootstrap system", NamedTextColor.YELLOW));
                            sender.sendMessage(Component.text("Please restart the server to reload environment configuration", NamedTextColor.GRAY));
                            return 1;
                            
                        }))
                .build();
    }
}
