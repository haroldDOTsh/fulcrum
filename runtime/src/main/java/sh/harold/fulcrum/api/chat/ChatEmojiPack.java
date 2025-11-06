package sh.harold.fulcrum.api.chat;

import sh.harold.fulcrum.api.rank.Rank;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Logical grouping for chat emojis. Packs can be unlocked per-player.
 */
public enum ChatEmojiPack {
    /**
     * Core emojis that every player can use.
     */
    CORE(true, null),

    /**
     * Celebration-themed emojis that require an explicit unlock.
     */
    CELEBRATION(false, null),

    /**
     * Utility emojis reserved for staff ranks and above unless explicitly granted.
     */
    STAFF(false, Rank.HELPER);

    private static final Set<ChatEmojiPack> DEFAULT_PACKS;

    static {
        EnumSet<ChatEmojiPack> defaults = EnumSet.noneOf(ChatEmojiPack.class);
        for (ChatEmojiPack pack : values()) {
            if (pack.unlockedByDefault) {
                defaults.add(pack);
            }
        }
        DEFAULT_PACKS = Collections.unmodifiableSet(defaults);
    }

    private final boolean unlockedByDefault;
    private final Rank minimumRank;

    ChatEmojiPack(boolean unlockedByDefault, Rank minimumRank) {
        this.unlockedByDefault = unlockedByDefault;
        this.minimumRank = minimumRank;
    }

    /**
     * Creates a fresh set containing every pack unlocked by default.
     *
     * @return mutable enum set of default packs
     */
    public static EnumSet<ChatEmojiPack> createDefaultUnlocked() {
        return EnumSet.copyOf(DEFAULT_PACKS);
    }

    /**
     * @return immutable view of all packs unlocked by default.
     */
    public static Set<ChatEmojiPack> defaultUnlocked() {
        return DEFAULT_PACKS;
    }

    public boolean unlockedByDefault() {
        return unlockedByDefault;
    }

    public Optional<Rank> minimumRank() {
        return Optional.ofNullable(minimumRank);
    }

    /**
     * Determines whether the provided rank automatically unlocks this pack.
     *
     * @param rank player rank
     * @return true if the rank satisfies the pack's minimum requirement
     */
    public boolean autoUnlocksFor(Rank rank) {
        if (minimumRank == null || rank == null) {
            return false;
        }
        return rank.getPriority() >= minimumRank.getPriority();
    }
}
