package sh.harold.fulcrum.fundamentals.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatEmoji;
import sh.harold.fulcrum.api.chat.ChatEmojiPack;
import sh.harold.fulcrum.api.chat.ChatEmojiPackService;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

final class ChatEmojiServiceImpl implements ChatEmojiService {
    private final RankService rankService;
    private final ChatEmojiPackService packService;

    ChatEmojiServiceImpl(RankService rankService, ChatEmojiPackService packService) {
        this.rankService = rankService;
        this.packService = packService;
    }

    @Override
    public Component apply(Player player, Component message) {
        if (player == null || message == null) {
            return message;
        }
        Set<ChatEmojiPack> unlocked = resolveUnlockedPacks(player.getUniqueId());
        if (unlocked.isEmpty()) {
            return message;
        }

        Component current = message;
        for (ChatEmoji emoji : ChatEmoji.values()) {
            if (!unlocked.contains(emoji.pack())) {
                continue;
            }
            current = current.replaceText(builder -> builder
                    .match(emoji.pattern())
                    .replacement(emoji.component()));
        }
        return current;
    }

    private Set<ChatEmojiPack> resolveUnlockedPacks(UUID playerId) {
        if (playerId == null) {
            return Collections.emptySet();
        }

        EnumSet<ChatEmojiPack> packs = ChatEmojiPack.createDefaultUnlocked();

        if (packService != null) {
            packs.addAll(packService.getUnlockedPacks(playerId));
        }

        Rank rank = rankService != null ? rankService.getEffectiveRankSync(playerId) : null;
        if (rank != null) {
            for (ChatEmojiPack pack : ChatEmojiPack.values()) {
                if (pack.autoUnlocksFor(rank)) {
                    packs.add(pack);
                }
            }
        }

        if (packs.isEmpty()) {
            return Collections.emptySet();
        }
        return packs;
    }
}
