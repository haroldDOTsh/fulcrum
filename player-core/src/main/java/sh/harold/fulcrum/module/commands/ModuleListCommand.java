package sh.harold.fulcrum.module.commands;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
                .build();
    }
}
