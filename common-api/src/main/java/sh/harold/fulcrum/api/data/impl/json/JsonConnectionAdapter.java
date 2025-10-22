package sh.harold.fulcrum.api.data.impl.json;

import com.mongodb.client.MongoDatabase;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;

import java.nio.file.Path;
import java.util.Optional;

/**
 * JSON file-based implementation of ConnectionAdapter.
 * This is a placeholder - full implementation to be added.
 */
public class JsonConnectionAdapter implements ConnectionAdapter {

    private final Path storagePath;
    private final CacheProvider cacheProvider;

    public JsonConnectionAdapter(Path storagePath) {
        this(storagePath, null);
    }

    public JsonConnectionAdapter(Path storagePath, CacheProvider cacheProvider) {
        this.storagePath = storagePath;
        this.cacheProvider = cacheProvider;
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.JSON;
    }

    @Override
    public MongoDatabase getMongoDatabase() {
        return null;
    }

    @Override
    public Path getJsonStoragePath() {
        return storagePath;
    }

    @Override
    public Optional<CacheProvider> getCacheProvider() {
        return Optional.ofNullable(cacheProvider);
    }
}