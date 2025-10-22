package sh.harold.fulcrum.fundamentals.chat.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelType;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class ChatCommands {
    private final ChatChannelService service;

    public ChatCommands(ChatChannelService service) {
        this.service = service;
    }

    public LiteralCommandNode<CommandSourceStack> buildRoot() {
        return literal("chat")
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    sendUsage(ctx.getSource().getSender());
                    return 0;
                })
                .then(literal("all").executes(ctx -> switchChannel(ctx.getSource(), ChatChannelType.ALL)))
                .then(literal("party").executes(ctx -> switchChannel(ctx.getSource(), ChatChannelType.PARTY)))
                .then(literal("staff").executes(ctx -> switchChannel(ctx.getSource(), ChatChannelType.STAFF)))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildPartySend() {
        return literal("pc")
                .requires(source -> source.getSender() instanceof Player)
                .then(argument("message", greedyString())
                        .executes(ctx -> quickSend(ctx.getSource(), ChatChannelType.PARTY,
                                ctx.getArgument("message", String.class))))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text("Usage: /pc <message>", NamedTextColor.RED));
                    return 0;
                })
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildStaffSend() {
        return literal("sc")
                .requires(source -> source.getSender() instanceof Player)
                .then(argument("message", greedyString())
                        .executes(ctx -> quickSend(ctx.getSource(), ChatChannelType.STAFF,
                                ctx.getArgument("message", String.class))))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text("Usage: /sc <message>", NamedTextColor.RED));
                    return 0;
                })
                .build();
    }

    private int switchChannel(CommandSourceStack source, ChatChannelType type) {
        Player player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        service.switchChannel(player, type);
        return 1;
    }

    private int quickSend(CommandSourceStack source, ChatChannelType type, String message) {
        Player player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        service.quickSend(player, type, message);
        return 1;
    }

    private Player requirePlayer(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
        return null;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /chat <all|party|staff>", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Quick send: /pc <message>, /sc <message>", NamedTextColor.GRAY));
    }
}
