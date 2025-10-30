package sh.harold.fulcrum.fundamentals.chat.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class DisableMeCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("me")
                .executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    sender.sendMessage(Component.text("That command is disabled on this network.", NamedTextColor.RED));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
