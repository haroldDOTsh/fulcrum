package sh.harold.fulcrum.api.data.impl.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import com.mongodb.client.MongoDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

/**
 * PostgreSQL implementation of ConnectionAdapter.
 * Manages PostgreSQL connections with HikariCP connection pooling.
 */
public class PostgresConnectionAdapter implements ConnectionAdapter {
    
    private final HikariDataSource dataSource;
    private final String databaseName;
    private final CacheProvider cacheProvider;
    
    /**
     * Create a new PostgreSQL connection adapter with default configuration.
     * 
     * @param jdbcUrl The JDBC URL for PostgreSQL connection
     * @param username The database username
     * @param password The database password
     * @param databaseName The database name
     */
    public PostgresConnectionAdapter(String jdbcUrl, String username, String password, String databaseName) {
        this(jdbcUrl, username, password, databaseName, null, new Properties());
    }
    
    /**
     * Create a new PostgreSQL connection adapter with cache provider.
     * 
     * @param jdbcUrl The JDBC URL for PostgreSQL connection
     * @param username The database username
     * @param password The database password
     * @param databaseName The database name
     * @param cacheProvider Optional cache provider
     */
    public PostgresConnectionAdapter(String jdbcUrl, String username, String password, String databaseName, 
                                    CacheProvider cacheProvider) {
        this(jdbcUrl, username, password, databaseName, cacheProvider, new Properties());
    }
    
    /**
     * Create a new PostgreSQL connection adapter with full configuration.
     * 
     * @param jdbcUrl The JDBC URL for PostgreSQL connection
     * @param username The database username
     * @param password The database password
     * @param databaseName The database name
     * @param cacheProvider Optional cache provider
     * @param additionalProperties Additional HikariCP properties
     */
    public PostgresConnectionAdapter(String jdbcUrl, String username, String password, String databaseName,
                                    CacheProvider cacheProvider, Properties additionalProperties) {
        this.databaseName = databaseName;
        this.cacheProvider = cacheProvider;
        
        // Configure HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Connection pool settings
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setIdleTimeout(300000); // 5 minutes
        config.setConnectionTimeout(30000); // 30 seconds
        config.setMaxLifetime(1800000); // 30 minutes
        config.setLeakDetectionThreshold(60000); // 1 minute
        
        // PostgreSQL specific settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        
        // Connection test query
        config.setConnectionTestQuery("SELECT 1");
        
        // Pool name for monitoring
        config.setPoolName("FulcrumPostgresPool-" + databaseName);
        
        // Apply additional properties if provided
        if (additionalProperties != null) {
            additionalProperties.forEach((key, value) -> {
                if (key instanceof String && value instanceof String) {
                    config.addDataSourceProperty((String) key, (String) value);
                }
            });
        }
        
        this.dataSource = new HikariDataSource(config);
    }
    
    @Override
    public StorageType getStorageType() {
        return StorageType.POSTGRES;
    }
    
    @Override
    public MongoDatabase getMongoDatabase() {
        // Not applicable for PostgreSQL storage
        return null;
    }
    
    @Override
    public Path getJsonStoragePath() {
        // Not applicable for PostgreSQL storage
        return null;
    }
    
    @Override
    public Optional<CacheProvider> getCacheProvider() {
        return Optional.ofNullable(cacheProvider);
    }
    
    /**
     * Get a connection from the pool.
     * 
     * @return A database connection
     * @throws SQLException if connection cannot be obtained
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * Get the HikariCP data source.
     * 
     * @return The data source
     */
    public HikariDataSource getDataSource() {
        return dataSource;
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
     * Get pool statistics.
     * 
     * @return Pool statistics as a formatted string
     */
    public String getPoolStats() {
        if (dataSource != null) {
            return String.format("Active: %d, Idle: %d, Total: %d, Waiting: %d",
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getTotalConnections(),
                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }
        return "Pool not initialized";
    }
    
    /**
     * Close the connection pool.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
    
    /**
     * Check if the connection pool is closed.
     * 
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return dataSource == null || dataSource.isClosed();
    }
}