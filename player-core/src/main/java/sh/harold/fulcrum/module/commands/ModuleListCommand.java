package sh.harold.fulcrum.module.commands;

import static io.papermc.paper.command.brigadier.Commands.literal;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import sh.harold.fulcrum.module.ModuleManager;
import sh.harold.fulcrum.module.ModuleMetadata;

public final class ModuleListCommand {

    private final ModuleManager moduleManager;

    public ModuleListCommand(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("fulcrummodules")
                .requires(source -> source.getSender().hasPermission("fulcrum.modules.list"))
                .then(literal("list")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            var sender = source.getSender();

                            sender.sendMessage(Component.text("Loaded Fulcrum Modules:", NamedTextColor.GOLD));

                            for (ModuleMetadata metadata : moduleManager.getLoadedModules()) {
                                Component line = Component.text("• ", NamedTextColor.GRAY)
                                        .append(Component.text(metadata.name(), NamedTextColor.GREEN))
                                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                                        .append(Component.text(metadata.description(), NamedTextColor.WHITE));
                                sender.sendMessage(line);
                            }

                            return 1;
                        }))
                .build();
    }
}
