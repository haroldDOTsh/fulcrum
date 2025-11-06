package sh.harold.fulcrum.fundamentals.chat;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.chat.ChatEmojiPack;
import sh.harold.fulcrum.api.chat.ChatEmojiPackService;
import sh.harold.fulcrum.common.cache.PlayerCache;

import java.util.*;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

final class PersistentChatEmojiPackServiceTest {
    private final FakePlayerCache playerCache = new FakePlayerCache();
    private final ChatEmojiPackService service = new PersistentChatEmojiPackService(playerCache);

    @Test
    void returnsDefaultWhenNoUnlocksStored() {
        UUID playerId = UUID.randomUUID();
        Set<ChatEmojiPack> packs = service.getUnlockedPacks(playerId);

        assertTrue(packs.contains(ChatEmojiPack.CORE));
        assertFalse(packs.contains(ChatEmojiPack.CELEBRATION));
    }

    @Test
    void grantingPackPersistsToPlayerDocument() {
        UUID playerId = UUID.randomUUID();

        service.grantPack(playerId, ChatEmojiPack.CELEBRATION).join();

        Set<ChatEmojiPack> packs = service.getUnlockedPacks(playerId);
        assertTrue(packs.contains(ChatEmojiPack.CELEBRATION));

        Map<String, Object> snapshot = playerCache.cosmetics(playerId).snapshot();
        @SuppressWarnings("unchecked")
        List<String> stored = (List<String>) snapshot.get("emojiPacks");
        assertNotNull(stored);
        assertTrue(stored.contains(ChatEmojiPack.CELEBRATION.name()));
    }

    @Test
    void revokingPackRemovesFromDocument() {
        UUID playerId = UUID.randomUUID();
        service.grantPack(playerId, ChatEmojiPack.CELEBRATION).join();

        service.revokePack(playerId, ChatEmojiPack.CELEBRATION).join();

        Set<ChatEmojiPack> packs = service.getUnlockedPacks(playerId);
        assertFalse(packs.contains(ChatEmojiPack.CELEBRATION));

        Map<String, Object> snapshot = playerCache.cosmetics(playerId).snapshot();
        @SuppressWarnings("unchecked")
        List<String> stored = (List<String>) snapshot.get("emojiPacks");
        assertTrue(stored == null || stored.isEmpty());
    }

    private static final class FakePlayerCache implements PlayerCache {
        private final Map<UUID, PlayerState> documents = new HashMap<>();
        private final Executor executor = Runnable::run;

        @Override
        public CachedDocument root(UUID playerId) {
            return documents.computeIfAbsent(playerId, ignored -> new PlayerState(executor)).settingsDocument;
        }

        @Override
        public CachedDocument cosmetics(UUID playerId) {
            return documents.computeIfAbsent(playerId, ignored -> new PlayerState(executor)).cosmeticsDocument;
        }

        @Override
        public CachedDocument scoped(String family, String variant, UUID playerId) {
            throw new UnsupportedOperationException("Scoped documents not supported in fake cache");
        }

        @Override
        public Executor asyncExecutor() {
            return executor;
        }

        private static final class PlayerState {
            private final Map<String, Object> settings = new LinkedHashMap<>();
            private final Map<String, Object> cosmetics = new LinkedHashMap<>();
            private final CachedDocument settingsDocument;
            private final CachedDocument cosmeticsDocument;

            private PlayerState(Executor executor) {
                cosmetics.put("emojiPacks", new ArrayList<>());
                settingsDocument = new MapBackedDocument(settings, executor);
                cosmeticsDocument = new MapBackedDocument(cosmetics, executor);
            }
        }

        private record MapBackedDocument(Map<String, Object> root, Executor executor) implements CachedDocument {

            @Override
                    public Executor asyncExecutor() {
                        return executor;
                    }

                    @Override
                    public <T> Optional<T> get(String key, Class<T> type) {
                        Object value = readPath(key);
                        if (!type.isInstance(value)) {
                            return Optional.empty();
                        }
                        @SuppressWarnings("unchecked")
                        T cast = (T) deepCopy(value);
                        return Optional.of(cast);
                    }

                    @Override
                    public void set(String key, Object value) {
                        if (value == null) {
                            remove(key);
                            return;
                        }
                        writePath(key, deepCopy(value));
                    }

                    @Override
                    public void remove(String key) {
                        deletePath(key);
                    }

                    @Override
                    public Map<String, Object> snapshot() {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> copy = (Map<String, Object>) deepCopy(root);
                        return copy;
                    }

                    private Object readPath(String key) {
                        if (key == null || key.isBlank()) {
                            return root;
                        }
                        String[] parts = key.split("\\.");
                        Object current = root;
                        for (String part : parts) {
                            if (!(current instanceof Map<?, ?> map)) {
                                return null;
                            }
                            current = map.get(part);
                            if (current == null) {
                                return null;
                            }
                        }
                        return current;
                    }

                    private void writePath(String key, Object value) {
                        if (key == null || key.isBlank()) {
                            if (value instanceof Map<?, ?> map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> copy = (Map<String, Object>) deepCopy(map);
                                root.clear();
                                root.putAll(copy);
                            }
                            return;
                        }
                        String[] parts = key.split("\\.");
                        Map<String, Object> current = root;
                        for (int i = 0; i < parts.length - 1; i++) {
                            String part = parts[i];
                            Object next = current.get(part);
                            if (!(next instanceof Map<?, ?>)) {
                                Map<String, Object> created = new LinkedHashMap<>();
                                current.put(part, created);
                                current = created;
                            } else {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> typed = (Map<String, Object>) next;
                                current = typed;
                            }
                        }
                        current.put(parts[parts.length - 1], value);
                    }

                    private void deletePath(String key) {
                        if (key == null || key.isBlank()) {
                            root.clear();
                            return;
                        }
                        String[] parts = key.split("\\.");
                        Map<String, Object> current = root;
                        for (int i = 0; i < parts.length - 1; i++) {
                            String part = parts[i];
                            Object next = current.get(part);
                            if (!(next instanceof Map<?, ?>)) {
                                return;
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) next;
                            current = typed;
                        }
                        current.remove(parts[parts.length - 1]);
                    }

                    private Object deepCopy(Object value) {
                        if (value instanceof Map<?, ?> map) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            map.forEach((k, v) -> copy.put(String.valueOf(k), deepCopy(v)));
                            return copy;
                        }
                        if (value instanceof List<?> list) {
                            List<Object> copy = new ArrayList<>();
                            list.forEach(element -> copy.add(deepCopy(element)));
                            return copy;
                        }
                        return value;
                    }
                }
    }
}
