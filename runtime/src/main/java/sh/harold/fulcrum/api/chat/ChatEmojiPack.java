package sh.harold.fulcrum.api.chat;

import sh.harold.fulcrum.api.rank.Rank;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
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
    private static final String NAMESPACE_PREFIX = "emoji:pack:";

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
     * Returns the namespaced identifier for this pack suitable for storage.
     *
     * @return namespaced id (e.g. emoji:pack:celebration)
     */
    public String namespacedId() {
        return NAMESPACE_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a stored namespaced token into a pack.
     *
     * @param token stored token
     * @return optional pack
     */
    public static Optional<ChatEmojiPack> fromToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith(NAMESPACE_PREFIX)) {
            return Optional.empty();
        }
        normalized = normalized.substring(NAMESPACE_PREFIX.length()).toUpperCase(Locale.ROOT);
        try {
            return Optional.of(ChatEmojiPack.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
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
