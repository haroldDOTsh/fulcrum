package sh.harold.fulcrum.runtime.cache;

import sh.harold.fulcrum.api.data.dirty.DirtyDataCache;
import sh.harold.fulcrum.api.data.dirty.DirtyDataEntry;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Base wrapper class for dirty data caches that provides a foundation for
 * adding monitoring, resilience, and other cross-cutting concerns.
 */
public abstract class DirtyCacheWrapper implements DirtyDataCache {
    
    protected final DirtyDataCache delegate;
    
    /**
     * Creates a new cache wrapper.
     * 
     * @param delegate The underlying cache implementation
     */
    public DirtyCacheWrapper(DirtyDataCache delegate) {
        this.delegate = delegate;
    }
    
    /**
     * Called before each operation for monitoring purposes.
     * 
     * @param operation The operation name
     */
    protected void onOperation(String operation) {
        // Default implementation does nothing
    }
    
    /**
     * Called when an operation encounters an error.
     * 
     * @param operation The operation name
     * @param error The error that occurred
     */
    protected void onError(String operation, Exception error) {
        // Default implementation does nothing
    }
    
    @Override
    public void markDirty(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        onOperation("markDirty");
        try {
            delegate.markDirty(playerId, schemaKey, data, changeType);
        } catch (Exception e) {
            onError("markDirty", e);
            throw e;
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId, String schemaKey) {
        onOperation("isDirty");
        try {
            return delegate.isDirty(playerId, schemaKey);
        } catch (Exception e) {
            onError("isDirty", e);
            throw e;
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId) {
        onOperation("isDirty");
        try {
            return delegate.isDirty(playerId);
        } catch (Exception e) {
            onError("isDirty", e);
            throw e;
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntries(UUID playerId) {
        onOperation("getDirtyEntries");
        try {
            return delegate.getDirtyEntries(playerId);
        } catch (Exception e) {
            onError("getDirtyEntries", e);
            throw e;
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getAllDirtyEntries() {
        onOperation("getAllDirtyEntries");
        try {
            return delegate.getAllDirtyEntries();
        } catch (Exception e) {
            onError("getAllDirtyEntries", e);
            throw e;
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntriesOlderThan(Instant threshold) {
        onOperation("getDirtyEntriesOlderThan");
        try {
            return delegate.getDirtyEntriesOlderThan(threshold);
        } catch (Exception e) {
            onError("getDirtyEntriesOlderThan", e);
            throw e;
        }
    }
    
    @Override
    public void clearDirty(UUID playerId, String schemaKey) {
        onOperation("clearDirty");
        try {
            delegate.clearDirty(playerId, schemaKey);
        } catch (Exception e) {
            onError("clearDirty", e);
            throw e;
        }
    }
    
    @Override
    public void clearDirty(UUID playerId) {
        onOperation("clearDirty");
        try {
            delegate.clearDirty(playerId);
        } catch (Exception e) {
            onError("clearDirty", e);
            throw e;
        }
    }
    
    @Override
    public void clearAllDirty() {
        onOperation("clearAllDirty");
        try {
            delegate.clearAllDirty();
        } catch (Exception e) {
            onError("clearAllDirty", e);
            throw e;
        }
    }
    
    @Override
    public int getDirtyCount(UUID playerId) {
        onOperation("getDirtyCount");
        try {
            return delegate.getDirtyCount(playerId);
        } catch (Exception e) {
            onError("getDirtyCount", e);
            throw e;
        }
    }
    
    @Override
    public int getTotalDirtyCount() {
        onOperation("getTotalDirtyCount");
        try {
            return delegate.getTotalDirtyCount();
        } catch (Exception e) {
            onError("getTotalDirtyCount", e);
            throw e;
        }
    }
    
    @Override
    public boolean supportsPersistence() {
        return delegate.supportsPersistence();
    }
    
    @Override
    public void cleanup() {
        onOperation("cleanup");
        try {
            delegate.cleanup();
        } catch (Exception e) {
            onError("cleanup", e);
            throw e;
        }
    }
    
    @Override
    public CompletableFuture<Void> markDirtyAsync(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        onOperation("markDirtyAsync");
        try {
            return delegate.markDirtyAsync(playerId, schemaKey, data, changeType);
        } catch (Exception e) {
            onError("markDirtyAsync", e);
            throw e;
        }
    }
    
    @Override
    public CompletableFuture<Collection<DirtyDataEntry>> getDirtyEntriesAsync(UUID playerId) {
        onOperation("getDirtyEntriesAsync");
        try {
            return delegate.getDirtyEntriesAsync(playerId);
        } catch (Exception e) {
            onError("getDirtyEntriesAsync", e);
            throw e;
        }
    }
    
    @Override
    public CompletableFuture<Void> clearDirtyAsync(UUID playerId, String schemaKey) {
        onOperation("clearDirtyAsync");
        try {
            return delegate.clearDirtyAsync(playerId, schemaKey);
        } catch (Exception e) {
            onError("clearDirtyAsync", e);
            throw e;
        }
    }
}