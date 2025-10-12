package sh.harold.fulcrum.api.data.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB implementation of ConnectionAdapter.
 * Manages MongoDB client connections and database access.
 */
public class MongoConnectionAdapter implements ConnectionAdapter {

    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final String databaseName;
    private final CacheProvider cacheProvider;

    /**
     * Create a new MongoDB connection adapter with connection string.
     *
     * @param connectionString The MongoDB connection string
     * @param databaseName     The database name to use
     */
    public MongoConnectionAdapter(String connectionString, String databaseName) {
        this(connectionString, databaseName, null);
    }

    /**
     * Create a new MongoDB connection adapter with connection string and cache.
     *
     * @param connectionString The MongoDB connection string
     * @param databaseName     The database name to use
     * @param cacheProvider    Optional cache provider
     */
    public MongoConnectionAdapter(String connectionString, String databaseName, CacheProvider cacheProvider) {
        this.databaseName = databaseName;
        this.cacheProvider = cacheProvider;

        // Configure MongoDB client settings
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .applyToConnectionPoolSettings(builder ->
                        builder.maxSize(100)
                                .minSize(10)
                                .maxWaitTime(60, TimeUnit.SECONDS)
                                .maxConnectionLifeTime(30, TimeUnit.MINUTES)
                                .maxConnectionIdleTime(10, TimeUnit.MINUTES))
                .applyToSocketSettings(builder ->
                        builder.connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(15, TimeUnit.SECONDS))
                .retryWrites(true)
                .retryReads(true)
                .build();

        this.mongoClient = MongoClients.create(settings);
        this.database = mongoClient.getDatabase(databaseName);
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.MONGODB;
    }

    @Override
    public MongoDatabase getMongoDatabase() {
        return database;
    }

    @Override
    public Path getJsonStoragePath() {
        // Not applicable for MongoDB storage
        return null;
    }

    @Override
    public Optional<CacheProvider> getCacheProvider() {
        return Optional.ofNullable(cacheProvider);
    }

    /**
     * Get the MongoDB client instance.
     *
     * @return The MongoDB client
     */
    public MongoClient getMongoClient() {
        return mongoClient;
    }

    /**
     * Get the database name.
     *
     * @return The database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Close the MongoDB connection.
     */
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}