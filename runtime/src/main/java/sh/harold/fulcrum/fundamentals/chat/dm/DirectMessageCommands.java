package sh.harold.fulcrum.fundamentals.chat.dm;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

final class DirectMessageCommands {
    private final DirectMessageService service;

    DirectMessageCommands(DirectMessageService service) {
        this.service = service;
    }

    LiteralCommandNode<CommandSourceStack> buildMessageCommand(String alias) {
        return literal(alias)
                .requires(stack -> stack.getSender() instanceof Player)
                .then(argument("target", word())
                        .then(argument("message", greedyString())
                                .executes(ctx -> {
                                    Player player = requirePlayer(ctx.getSource());
                                    if (player == null) {
                                        return 0;
                                    }
                                    String target = ctx.getArgument("target", String.class);
                                    String message = ctx.getArgument("message", String.class);
                                    service.sendMessage(player, target, message);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            Player player = requirePlayer(ctx.getSource());
                            if (player == null) {
                                return 0;
                            }
                            String target = ctx.getArgument("target", String.class);
                            service.openChannel(player, target);
                            return 1;
                        }))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text("Usage: /" + alias + " <player> [message]", NamedTextColor.RED));
                    return 0;
                })
                .build();
    }

    LiteralCommandNode<CommandSourceStack> buildReplyCommand(String alias) {
        return literal(alias)
                .requires(stack -> stack.getSender() instanceof Player)
                .then(argument("message", greedyString())
                        .executes(ctx -> {
                            Player player = requirePlayer(ctx.getSource());
                            if (player == null) {
                                return 0;
                            }
                            String message = ctx.getArgument("message", String.class);
                            service.reply(player, message);
                            return 1;
                        }))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text("Usage: /" + alias + " <message>", NamedTextColor.RED));
                    return 0;
                })
                .build();
    }

    private Player requirePlayer(CommandSourceStack stack) {
        CommandSender sender = stack.getSender();
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
        return null;
    }
}
