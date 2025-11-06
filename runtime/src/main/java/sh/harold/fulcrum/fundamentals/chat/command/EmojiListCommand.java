package sh.harold.fulcrum.fundamentals.chat.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatEmoji;
import sh.harold.fulcrum.api.chat.ChatEmojiPack;
import sh.harold.fulcrum.api.chat.ChatEmojiPackService;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class EmojiListCommand {
    private final ChatEmojiPackService packService;

    public EmojiListCommand(ChatEmojiPackService packService) {
        this.packService = Objects.requireNonNull(packService, "packService");
    }

    public LiteralCommandNode<CommandSourceStack> build(String literalName) {
        return literal(literalName)
                .requires(source -> source.getSender() instanceof Player)
                .executes(ctx -> {
                    execute(ctx.getSource());
                    return 1;
                })
                .build();
    }

    private void execute(CommandSourceStack source) {
        Player player = requirePlayer(source.getSender());
        if (player == null) {
            return;
        }

        Set<ChatEmojiPack> unlockedPacks = packService.getUnlockedPacks(player.getUniqueId());

        player.sendMessage(Component.text("Chat Emojis", NamedTextColor.GOLD));
        player.sendMessage(Component.text("  Use tokens in chat (e.g. :heart:)", NamedTextColor.GRAY));
        for (ChatEmoji emoji : ChatEmoji.values()) {
            boolean unlocked = unlockedPacks.contains(emoji.pack());
            player.sendMessage(buildLine(emoji, unlocked));
        }
    }

    private Component buildLine(ChatEmoji emoji, boolean unlocked) {
        TextComponent.Builder line = Component.text();
        line.append(Component.text("  ", NamedTextColor.GRAY));

        TextComponent token = Component.text(
                        emoji.token(),
                        unlocked ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.STRIKETHROUGH, !unlocked);
        line.append(token);

        line.append(Component.text("  ", NamedTextColor.GRAY));
        line.append(Component.text("- ", NamedTextColor.GRAY));

        TextComponent name = Component.text(
                        displayName(emoji),
                        unlocked ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.STRIKETHROUGH, !unlocked);
        line.append(name);

        return line.build();
    }

    private String displayName(ChatEmoji emoji) {
        return emoji.name()
                .toUpperCase(Locale.ROOT)
                .replace('_', ' ');
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
        return null;
    }
}
