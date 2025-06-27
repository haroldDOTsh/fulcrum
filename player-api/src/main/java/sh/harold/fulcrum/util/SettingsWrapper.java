package sh.harold.fulcrum.playerdata;

import java.util.*;
import java.util.concurrent.locks.*;

/**
 * Utility for dot-path access to arbitrarily nested Map<String, Object> structures.
 * Thread-safe for concurrent use.
 */
public class SettingsWrapper {
    private final Map<String, Object> root;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Wraps the given root map. Changes are reflected in the original map.
     * @param root the root map to wrap
     */
    public SettingsWrapper(Map<String, Object> root) {
        this.root = Objects.requireNonNull(root, "root map cannot be null");
    }

    /**
     * Gets the value at the given dot-path, or null if not found.
     * @param path dot-separated path (e.g. "hud.scale")
     * @return the value, or null
     */
    public Object get(String path) {
        lock.readLock().lock();
        try {
            return resolvePath(path, false, false, null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the value at the given dot-path, cast to the given type.
     * @param path dot-separated path
     * @param type expected type
     * @return value or null if not found
     * @throws ClassCastException if the value is present but not of the given type
     */
    public <T> T get(String path, Class<T> type) {
        Object value = get(path);
        if (value == null) return null;
        return type.cast(value);
    }

    /**
     * Sets the value at the given dot-path, creating intermediate maps as needed.
     * @param path dot-separated path
     * @param value value to set
     */
    public void set(String path, Object value) {
        lock.writeLock().lock();
        try {
            resolvePath(path, true, false, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns true if the given dot-path exists.
     * @param path dot-separated path
     * @return true if present
     */
    public boolean contains(String path) {
        lock.readLock().lock();
        try {
            return resolvePath(path, false, true, null) != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes the value at the given dot-path if it exists.
     * @param path dot-separated path
     */
    public void remove(String path) {
        lock.writeLock().lock();
        try {
            String[] parts = path.split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) return;
                current = (Map<String, Object>) next;
            }
            current.remove(parts[parts.length - 1]);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the root map (with all changes applied).
     * @return the root map
     */
    public Map<String, Object> toMap() {
        lock.readLock().lock();
        try {
            return root;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Internal path resolver
    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, boolean create, boolean checkExists, Object setValue) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next == null) {
                if (create) {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(parts[i], newMap);
                    current = newMap;
                } else {
                    return null;
                }
            } else if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                if (create) {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(parts[i], newMap);
                    current = newMap;
                } else {
                    return null;
                }
            }
        }
        String leaf = parts[parts.length - 1];
        if (setValue != null) {
            current.put(leaf, setValue);
            return null;
        }
        if (checkExists) {
            return current.containsKey(leaf) ? Boolean.TRUE : null;
        }
        return current.get(leaf);
    }
}
