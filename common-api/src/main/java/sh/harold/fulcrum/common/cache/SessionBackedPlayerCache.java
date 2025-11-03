package sh.harold.fulcrum.common.cache;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link PlayerCache} backed by a session store and {@link DataAPI}.
 * Implementations supply the session accessors for their platform-specific session service.
 */
public abstract class SessionBackedPlayerCache implements PlayerCache {

    protected final DataAPI dataAPI;
    private final SessionAccess sessionAccess;
    private final Executor asyncExecutor;

    protected SessionBackedPlayerCache(DataAPI dataAPI, SessionAccess sessionAccess) {
        this(dataAPI, sessionAccess, ForkJoinPool.commonPool());
    }

    protected SessionBackedPlayerCache(DataAPI dataAPI, SessionAccess sessionAccess, Executor asyncExecutor) {
        this.dataAPI = Objects.requireNonNull(dataAPI, "dataAPI");
        this.sessionAccess = Objects.requireNonNull(sessionAccess, "sessionAccess");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
    }

    @SuppressWarnings("unchecked")
    private static Object readFromMap(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) {
            return deepCopy(root);
        }
        String[] parts = path.split("\\.");
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
        if (current instanceof Map<?, ?> || current instanceof List<?>) {
            return deepCopy(current);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void writeToMap(Map<String, Object> root, String path, Object value) {
        if (path == null || path.isBlank()) {
            root.clear();
            if (value instanceof Map<?, ?> map) {
                root.putAll(deepCopy(map));
            }
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = current.get(part);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(part, created);
                current = created;
            } else {
                current = (Map<String, Object>) next;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void removeFromMap(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) {
            root.clear();
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = current.get(part);
            if (!(next instanceof Map<?, ?> map)) {
                return;
            }
            current = (Map<String, Object>) next;
        }
        current.remove(parts[parts.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(String.valueOf(key), deepCopyValue(value)));
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SessionBackedPlayerCache::deepCopyValue).collect(Collectors.toCollection(ArrayList::new));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Object map) {
        if (map instanceof Map<?, ?> m) {
            return deepCopy(m);
        }
        return new LinkedHashMap<>();
    }

    @Override
    public CachedDocument root(UUID playerId) {
        return new RootDocument(playerId);
    }

    @Override
    public CachedDocument scoped(String family, String variant, UUID playerId) {
        return new ScopedDocument(playerId, Objects.requireNonNull(family, "family"), variant);
    }

    @Override
    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    protected interface SessionAccess {
        Optional<PlayerSessionRecord> getSession(UUID playerId);

        boolean withSession(UUID playerId, Consumer<PlayerSessionRecord> consumer);
    }

    private abstract class AbstractDocument implements CachedDocument {
        protected final UUID playerId;

        private AbstractDocument(UUID playerId) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        @Override
        public Executor asyncExecutor() {
            return SessionBackedPlayerCache.this.asyncExecutor();
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");

            AtomicReference<Object> holder = new AtomicReference<>();
            boolean hit = sessionAccess.withSession(playerId, record -> {
                ensureSessionData(record);
                Object value = readFromMap(targetMap(record, false), key);
                holder.set(value);
            });

            if (hit) {
                Object value = holder.get();
                return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
            }
            Object value = readFromDocument(key);
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        }

        @Override
        public void set(String key, Object value) {
            Objects.requireNonNull(key, "key");
            boolean hit = sessionAccess.withSession(playerId, record -> {
                ensureSessionData(record);
                Map<String, Object> map = targetMap(record, true);
                if (value == null) {
                    removeFromMap(map, key);
                } else {
                    writeToMap(map, key, value);
                }
            });
            if (hit) {
                return;
            }
            if (value == null) {
                removeFromDocument(key);
            } else {
                writeToDocument(key, value);
            }
        }

        @Override
        public void remove(String key) {
            set(key, null);
        }

        @Override
        public Map<String, Object> snapshot() {
            AtomicReference<Map<String, Object>> holder = new AtomicReference<>();
            boolean hit = sessionAccess.withSession(playerId, record -> {
                ensureSessionData(record);
                Map<String, Object> map = targetMap(record, false);
                holder.set(deepCopy(map));
            });
            if (hit) {
                return holder.get();
            }
            return deepCopy(readDocumentSnapshot());
        }

        protected abstract Map<String, Object> targetMap(PlayerSessionRecord record, boolean create);

        protected abstract Document targetDocument();

        protected abstract String collectionName();

        protected abstract String documentPrefix();

        protected abstract void ensureSessionData(PlayerSessionRecord record);

        private Object readFromDocument(String key) {
            Document document = targetDocument();
            if (!document.exists()) {
                return null;
            }
            return document.get(prefixPath(key), null);
        }

        private Map<String, Object> readDocumentSnapshot() {
            Document document = targetDocument();
            if (!document.exists()) {
                return Collections.emptyMap();
            }
            Object root = document.get(documentPrefix(), null);
            if (root instanceof Map<?, ?> map) {
                return deepCopy(map);
            }
            return Collections.emptyMap();
        }

        private void writeToDocument(String key, Object value) {
            Document document = targetDocument();
            String fullPath = prefixPath(key);
            if (!document.exists()) {
                Map<String, Object> settingsMap = new LinkedHashMap<>();
                String relative = stripSettingsPrefix(fullPath);
                if (relative.isEmpty() && value instanceof Map<?, ?> map) {
                    settingsMap.putAll(deepCopy(map));
                } else if (!relative.isEmpty()) {
                    writeToMap(settingsMap, relative, value);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("settings", settingsMap);
                dataAPI.collection(collectionName()).create(playerId.toString(), payload);
                return;
            }
            document.set(fullPath, value);
        }

        private void removeFromDocument(String key) {
            Document document = targetDocument();
            if (!document.exists()) {
                return;
            }
            Object raw = document.get("settings", null);
            Map<String, Object> settingsMap = raw instanceof Map<?, ?> map ? deepCopy(map) : new LinkedHashMap<>();
            String relative = stripSettingsPrefix(prefixPath(key));
            if (relative.isEmpty()) {
                settingsMap.clear();
            } else {
                removeFromMap(settingsMap, relative);
            }
            document.set("settings", settingsMap);
        }

        private String prefixPath(String key) {
            String prefix = documentPrefix();
            if (key == null || key.isBlank()) {
                return prefix;
            }
            return prefix + "." + key;
        }

        private String stripSettingsPrefix(String fullPath) {
            if (fullPath == null || fullPath.isBlank()) {
                return "";
            }
            if (!fullPath.startsWith("settings")) {
                return fullPath;
            }
            if (fullPath.equals("settings")) {
                return "";
            }
            return fullPath.substring("settings".length() + 1);
        }
    }

    private final class RootDocument extends AbstractDocument {
        private RootDocument(UUID playerId) {
            super(playerId);
        }

        @Override
        protected Map<String, Object> targetMap(PlayerSessionRecord record, boolean create) {
            return record.mutableSettings();
        }

        @Override
        protected Document targetDocument() {
            return dataAPI.collection("players").document(playerId.toString());
        }

        @Override
        protected String collectionName() {
            return "players";
        }

        @Override
        protected String documentPrefix() {
            return "settings";
        }

        @Override
        protected void ensureSessionData(PlayerSessionRecord record) {
            // Global settings are loaded as part of the base bootstrap state.
        }
    }

    private final class ScopedDocument extends AbstractDocument {
        private final String family;
        private final String variant;

        private ScopedDocument(UUID playerId, String family, String variant) {
            super(playerId);
            this.family = family;
            this.variant = variant != null && variant.isBlank() ? null : variant;
        }

        @Override
        protected Map<String, Object> targetMap(PlayerSessionRecord record, boolean create) {
            Map<String, Object> settingsRoot = record.getScopedSettings(family);
            if (variant == null) {
                return settingsRoot;
            }
            Object branch = settingsRoot.get(variant);
            if (branch instanceof Map<?, ?> mapBranch) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) mapBranch;
                return typed;
            }
            if (!create) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> created = new LinkedHashMap<>();
            settingsRoot.put(variant, created);
            return created;
        }

        @Override
        protected Document targetDocument() {
            return dataAPI.collection(collectionName()).document(playerId.toString());
        }

        @Override
        protected String collectionName() {
            return "player_data_" + family;
        }

        @Override
        protected String documentPrefix() {
            if (variant == null || variant.isBlank()) {
                return "settings";
            }
            return "settings." + variant;
        }

        @Override
        protected void ensureSessionData(PlayerSessionRecord record) {
            Map<String, Object> familyState = record.ensureScopedFamily(family);
            if (Boolean.TRUE.equals(familyState.get("__loaded"))) {
                return;
            }
            Map<String, Object> settingsRoot = record.getScopedSettings(family);
            settingsRoot.clear();
            settingsRoot.putAll(readFamilySnapshot());
            familyState.put("__loaded", Boolean.TRUE);
        }

        private Map<String, Object> readFamilySnapshot() {
            Document document = targetDocument();
            if (!document.exists()) {
                return new LinkedHashMap<>();
            }
            Object raw = document.get("settings", null);
            if (raw instanceof Map<?, ?> map) {
                return deepCopy(map);
            }
            return new LinkedHashMap<>();
        }
    }
}
