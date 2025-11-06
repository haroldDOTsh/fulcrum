package sh.harold.fulcrum.fundamentals.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatEmoji;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

import java.util.UUID;

final class ChatEmojiServiceImpl implements ChatEmojiService {
    private final RankService rankService;

    ChatEmojiServiceImpl(RankService rankService) {
        this.rankService = rankService;
    }

    @Override
    public Component apply(Player player, Component message) {
        if (player == null || message == null) {
            return message;
        }
        if (!canUseEmojis(player.getUniqueId())) {
            return message;
        }

        Component current = message;
        for (ChatEmoji emoji : ChatEmoji.values()) {
            current = current.replaceText(builder -> builder
                    .match(emoji.pattern())
                    .replacement(emoji.component()));
        }
        return current;
    }

    private boolean canUseEmojis(UUID playerId) {
        if (playerId == null || rankService == null) {
            return false;
        }
        Rank rank = rankService.getEffectiveRankSync(playerId);
        return rank != null && rank != Rank.DEFAULT;
    }
}
