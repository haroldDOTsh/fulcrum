package sh.harold.fulcrum.api.data.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class SettingsWrapper {
    private final Map<String, Object> root;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();


    public SettingsWrapper(Map<String, Object> root) {
        this.root = Objects.requireNonNull(root, "root map cannot be null");
    }


    public Object get(String path) {
        lock.readLock().lock();
        try {
            return resolvePath(path, false, false, null);
        } finally {
            lock.readLock().unlock();
        }
    }


    public <T> T get(String path, Class<T> type) {
        Object value = get(path);
        if (value == null) return null;
        return type.cast(value);
    }


    public void set(String path, Object value) {
        lock.writeLock().lock();
        try {
            resolvePath(path, true, false, value);
        } finally {
            lock.writeLock().unlock();
        }
    }


    public boolean contains(String path) {
        lock.readLock().lock();
        try {
            return resolvePath(path, false, true, null) != null;
        } finally {
            lock.readLock().unlock();
        }
    }


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
