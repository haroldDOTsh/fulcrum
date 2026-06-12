package sh.harold.fulcrum.api.data.impl;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageBackend;
import sh.harold.fulcrum.api.data.storage.StorageType;
import sh.harold.fulcrum.api.data.impl.json.JsonStorageBackend;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoStorageBackend;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresStorageBackend;
import sh.harold.fulcrum.api.data.transaction.Transaction;
import sh.harold.fulcrum.api.data.transaction.TransactionImpl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.Map;

/**
 * Default implementation of the DataAPI interface.
 * Routes operations to the appropriate storage backend.
 */
public class DataAPIImpl implements DataAPI {
    
    private final ConnectionAdapter adapter;
    private final StorageBackend backend;
    private final Map<String, Collection> collectionCache;
    private final Executor executor;
    
    private DataAPIImpl(ConnectionAdapter adapter, Executor executor) {
        this.adapter = adapter;
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
        this.backend = createBackend(adapter.getStorageType());
        this.collectionCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Factory method to create a DataAPI instance.
     * 
     * @param adapter The connection adapter for storage backend
     * @return A new DataAPI instance
     */
    public static DataAPI create(ConnectionAdapter adapter) {
        return new DataAPIImpl(adapter, ForkJoinPool.commonPool());
    }

    public static DataAPI create(ConnectionAdapter adapter, Executor executor) {
        return new DataAPIImpl(adapter, executor);
    }
    
    private StorageBackend createBackend(StorageType storageType) {
        switch (storageType) {
            case MONGODB:
                // MongoDB backend needs MongoConnectionAdapter
                if (adapter instanceof sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter) {
                    return new MongoStorageBackend((sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter) adapter, executor);
                }
                throw new IllegalStateException("MongoDB connection adapter not available");
            
            case JSON:
                // JSON backend needs the storage path from adapter
                if (adapter.getJsonStoragePath() != null) {
                    return new JsonStorageBackend(adapter.getJsonStoragePath(), 1000, true, executor);
                }
                throw new IllegalStateException("JSON storage path not available");
            
            case IN_MEMORY:
                return new InMemoryStorageBackend(executor);

            case POSTGRES:
                if (adapter instanceof sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter postgresAdapter) {
                    return new PostgresStorageBackend(postgresAdapter, executor);
                }
                throw new IllegalStateException("PostgreSQL connection adapter not available");
                
            default:
                return new InMemoryStorageBackend(executor);
        }
    }
    
    @Override
    public Collection from(String collection) {
        return collectionCache.computeIfAbsent(collection, 
            name -> new CollectionImpl(name, backend));
    }
    
    @Override
    public Collection collection(String collection) {
        return from(collection);
    }
    
    @Override
    public Collection players() {
        return from("players");
    }
    
    @Override
    public Document player(UUID id) {
        return players().select(id.toString());
    }
    
    @Override
    public Collection guilds() {
        return from("guilds");
    }
    
    @Override
    public Transaction transaction() {
        return new TransactionImpl(backend).begin();
    }
    
    @Override
    public Transaction transaction(Transaction.IsolationLevel isolationLevel) {
        return new TransactionImpl(backend, isolationLevel).begin();
    }

    @Override
    public StorageBackend getStorageBackend() {
        return backend;
    }
}
