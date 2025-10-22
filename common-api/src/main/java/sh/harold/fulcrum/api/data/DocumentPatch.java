package sh.harold.fulcrum.api.data;

import java.util.*;

/**
 * Represents a set of partial updates to apply to a document.
 * <p>
 * Supports $set, $unset, and $setOnInsert style operations with optional
 * upsert semantics. Storage backends are responsible for translating the patch
 * to their native update language.
 */
public final class DocumentPatch {

    private final Map<String, Object> setOperations;
    private final Set<String> unsetPaths;
    private final Map<String, Object> setOnInsertOperations;
    private final boolean upsert;

    private DocumentPatch(Builder builder) {
        this.setOperations = Collections.unmodifiableMap(new LinkedHashMap<>(builder.setOperations));
        this.unsetPaths = Collections.unmodifiableSet(new LinkedHashSet<>(builder.unsetPaths));
        this.setOnInsertOperations = Collections.unmodifiableMap(new LinkedHashMap<>(builder.setOnInsertOperations));
        this.upsert = builder.upsert;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void setValueAtPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = cast(next);
        }
        current.put(parts[parts.length - 1], value);
    }

    private static void removeValueAtPath(Map<String, Object> target, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        Map<String, Object>[] stack = new Map[parts.length];
        stack[0] = target;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                return;
            }
            current = cast(next);
            stack[i + 1] = current;
        }

        current.remove(parts[parts.length - 1]);

        // Clean up any now-empty parent maps
        for (int i = parts.length - 2; i >= 0; i--) {
            Map<String, Object> parent = stack[i];
            if (parent == null) {
                continue;
            }
            Object maybeChild = parent.get(parts[i]);
            if (maybeChild instanceof Map<?, ?> child && child.isEmpty()) {
                parent.remove(parts[i]);
            } else {
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    public Map<String, Object> getSetOperations() {
        return setOperations;
    }

    public Set<String> getUnsetPaths() {
        return unsetPaths;
    }

    public Map<String, Object> getSetOnInsertOperations() {
        return setOnInsertOperations;
    }

    public boolean isUpsert() {
        return upsert;
    }

    public boolean isEmpty() {
        return setOperations.isEmpty()
                && unsetPaths.isEmpty()
                && setOnInsertOperations.isEmpty();
    }

    /**
     * Apply the patch against an in-memory representation of a document.
     *
     * @param target             Map representing the document contents.
     * @param includeSetOnInsert Whether setOnInsert operations should be
     *                           applied (typically true when the document
     *                           didn't previously exist).
     */
    @SuppressWarnings("unchecked")
    public void applyToMap(Map<String, Object> target, boolean includeSetOnInsert) {
        Objects.requireNonNull(target, "target");

        for (String path : unsetPaths) {
            removeValueAtPath(target, path);
        }

        for (Map.Entry<String, Object> entry : setOperations.entrySet()) {
            setValueAtPath(target, entry.getKey(), entry.getValue());
        }

        if (includeSetOnInsert) {
            for (Map.Entry<String, Object> entry : setOnInsertOperations.entrySet()) {
                setValueAtPath(target, entry.getKey(), entry.getValue());
            }
        }
    }

    public static final class Builder {
        private final Map<String, Object> setOperations = new LinkedHashMap<>();
        private final Set<String> unsetPaths = new LinkedHashSet<>();
        private final Map<String, Object> setOnInsertOperations = new LinkedHashMap<>();
        private boolean upsert;

        private static void requirePath(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Path must not be null or blank");
            }
        }

        public Builder set(String path, Object value) {
            requirePath(path);
            setOperations.put(path, value);
            return this;
        }

        public Builder unset(String path) {
            requirePath(path);
            unsetPaths.add(path);
            return this;
        }

        public Builder setOnInsert(String path, Object value) {
            requirePath(path);
            setOnInsertOperations.put(path, value);
            return this;
        }

        public Builder upsert(boolean upsert) {
            this.upsert = upsert;
            return this;
        }

        public DocumentPatch build() {
            return new DocumentPatch(this);
        }
    }
}
