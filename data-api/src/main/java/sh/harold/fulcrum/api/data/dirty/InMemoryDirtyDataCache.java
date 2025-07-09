package sh.harold.fulcrum.api.data.dirty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Default implementation of DirtyDataCache using in-memory storage.
 * 
 * This implementation uses ConcurrentHashMap for thread-safe operations
 * and provides efficient lookups for dirty data entries.
 */
public class InMemoryDirtyDataCache implements DirtyDataCache {
    
    private final Map<String, DirtyDataEntry> dirtyEntries;
    private final ReadWriteLock lock;
    
    /**
     * Creates a new in-memory dirty data cache.
     */
    public InMemoryDirtyDataCache() {
        this.dirtyEntries = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    @Override
    public void markDirty(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        validateParameters(playerId, schemaKey, changeType);
        
        String key = createKey(playerId, schemaKey);
        DirtyDataEntry entry = new DirtyDataEntry(playerId, schemaKey, data, changeType);
        
        lock.writeLock().lock();
        try {
            dirtyEntries.put(key, entry);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId, String schemaKey) {
        validateParameters(playerId, schemaKey);
        
        String key = createKey(playerId, schemaKey);
        lock.readLock().lock();
        try {
            return dirtyEntries.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId) {
        validatePlayerId(playerId);
        
        String playerPrefix = playerId.toString() + ":";
        lock.readLock().lock();
        try {
            return dirtyEntries.keySet().stream()
                    .anyMatch(key -> key.startsWith(playerPrefix));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntries(UUID playerId) {
        validatePlayerId(playerId);
        
        String playerPrefix = playerId.toString() + ":";
        lock.readLock().lock();
        try {
            return dirtyEntries.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(playerPrefix))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getAllDirtyEntries() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(dirtyEntries.values());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntriesOlderThan(Instant threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold cannot be null");
        }
        
        lock.readLock().lock();
        try {
            return dirtyEntries.values().stream()
                    .filter(entry -> entry.isOlderThan(threshold))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public void clearDirty(UUID playerId, String schemaKey) {
        validateParameters(playerId, schemaKey);
        
        String key = createKey(playerId, schemaKey);
        lock.writeLock().lock();
        try {
            dirtyEntries.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void clearDirty(UUID playerId) {
        validatePlayerId(playerId);
        
        String playerPrefix = playerId.toString() + ":";
        lock.writeLock().lock();
        try {
            dirtyEntries.entrySet().removeIf(entry -> entry.getKey().startsWith(playerPrefix));
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void clearAllDirty() {
        lock.writeLock().lock();
        try {
            dirtyEntries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public int getDirtyCount(UUID playerId) {
        validatePlayerId(playerId);
        
        String playerPrefix = playerId.toString() + ":";
        lock.readLock().lock();
        try {
            return (int) dirtyEntries.keySet().stream()
                    .filter(key -> key.startsWith(playerPrefix))
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public int getTotalDirtyCount() {
        lock.readLock().lock();
        try {
            return dirtyEntries.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public void cleanup() {
        // For in-memory implementation, cleanup is a no-op
        // Subclasses could override this to implement expiration logic
    }
    
    /**
     * Creates a unique key for the given player ID and schema key.
     * 
     * @param playerId The player ID
     * @param schemaKey The schema key
     * @return A unique key string
     */
    private String createKey(UUID playerId, String schemaKey) {
        return playerId.toString() + ":" + schemaKey;
    }
    
    /**
     * Validates the required parameters for operations involving player ID and schema key.
     * 
     * @param playerId The player ID to validate
     * @param schemaKey The schema key to validate
     * @throws IllegalArgumentException if any parameter is null
     */
    private void validateParameters(UUID playerId, String schemaKey) {
        validatePlayerId(playerId);
        if (schemaKey == null) {
            throw new IllegalArgumentException("schemaKey cannot be null");
        }
    }
    
    /**
     * Validates the required parameters for marking data as dirty.
     * 
     * @param playerId The player ID to validate
     * @param schemaKey The schema key to validate
     * @param changeType The change type to validate
     * @throws IllegalArgumentException if any parameter is null
     */
    private void validateParameters(UUID playerId, String schemaKey, DirtyDataEntry.ChangeType changeType) {
        validateParameters(playerId, schemaKey);
        if (changeType == null) {
            throw new IllegalArgumentException("changeType cannot be null");
        }
    }
    
    /**
     * Validates the player ID parameter.
     * 
     * @param playerId The player ID to validate
     * @throws IllegalArgumentException if playerId is null
     */
    private void validatePlayerId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId cannot be null");
        }
    }
}