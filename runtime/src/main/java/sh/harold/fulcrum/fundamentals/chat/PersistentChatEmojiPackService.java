package sh.harold.fulcrum.fundamentals.chat;

import sh.harold.fulcrum.api.chat.ChatEmojiPack;
import sh.harold.fulcrum.api.chat.ChatEmojiPackService;
import sh.harold.fulcrum.common.cache.PlayerCache;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

final class PersistentChatEmojiPackService implements ChatEmojiPackService {
    private static final String PACKS_KEY = "emojiPacks";

    private final PlayerCache playerCache;
    private final Executor executor;

    PersistentChatEmojiPackService(PlayerCache playerCache) {
        this.playerCache = Objects.requireNonNull(playerCache, "playerCache");
        this.executor = playerCache.asyncExecutor();
    }

    @Override
    public Set<ChatEmojiPack> getUnlockedPacks(UUID playerId) {
        if (playerId == null) {
            return ChatEmojiPack.defaultUnlocked();
        }
        PlayerCache.CachedDocument document = playerCache.cosmetics(playerId);
        EnumSet<ChatEmojiPack> unlocked = ChatEmojiPack.createDefaultUnlocked();
        unlocked.addAll(loadExplicitPacks(document));
        return Collections.unmodifiableSet(unlocked);
    }

    @Override
    public CompletableFuture<Void> grantPack(UUID playerId, ChatEmojiPack pack) {
        if (playerId == null || pack == null || pack.unlockedByDefault()) {
            return CompletableFuture.completedFuture(null);
        }
        return mutatePacksAsync(playerId, packs -> packs.add(pack));
    }

    @Override
    public CompletableFuture<Void> revokePack(UUID playerId, ChatEmojiPack pack) {
        if (playerId == null || pack == null || pack.unlockedByDefault()) {
            return CompletableFuture.completedFuture(null);
        }
        return mutatePacksAsync(playerId, packs -> packs.remove(pack));
    }

    private CompletableFuture<Void> mutatePacksAsync(UUID playerId, java.util.function.Consumer<EnumSet<ChatEmojiPack>> mutator) {
        PlayerCache.CachedDocument document = playerCache.cosmetics(playerId);
        CompletionStage<Void> stage = CompletableFuture.runAsync(() -> mutateAndPersist(document, mutator), executor);
        return stage.toCompletableFuture();
    }

    private void mutateAndPersist(PlayerCache.CachedDocument document,
                                  java.util.function.Consumer<EnumSet<ChatEmojiPack>> mutator) {
        EnumSet<ChatEmojiPack> packs = loadExplicitPacks(document);
        mutator.accept(packs);
        packs.removeIf(ChatEmojiPack::unlockedByDefault);

        if (packs.isEmpty()) {
            document.remove(PACKS_KEY);
            return;
        }

        List<String> serialized = packs.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        document.set(PACKS_KEY, serialized);
    }

    private EnumSet<ChatEmojiPack> loadExplicitPacks(PlayerCache.CachedDocument document) {
        EnumSet<ChatEmojiPack> packs = EnumSet.noneOf(ChatEmojiPack.class);
        Optional<List> stored = document.get(PACKS_KEY, List.class);
        if (stored.isEmpty()) {
            return packs;
        }
        for (Object entry : stored.get()) {
            if (entry instanceof String token && !token.isBlank()) {
                try {
                    packs.add(ChatEmojiPack.valueOf(token.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid tokens and allow cleanup on next persist.
                }
            }
        }
        return packs;
    }
}
