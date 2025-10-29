package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.nio.file.Path;

public class VelocityDataAPIFeature implements VelocityFeature {
    private Logger logger;
    private VelocityConnectionAdapter connectionAdapter;
    private DataAPI dataAPI;
    private ConnectionAdapter connection;
    private PostgresConnectionAdapter postgresAdapter;
    private MongoConnectionAdapter mongoAdapter;
    private ProxyServer proxy;
    private Path dataDirectory;
    private ServiceLocator serviceLocator;

    @Override
    public String getName() {
        return "DataAPI";
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;

        // Get proxy server and data directory from service locator
        this.proxy = serviceLocator.getService(ProxyServer.class).orElse(null);
        this.dataDirectory = serviceLocator.getService(Path.class).orElse(null);

        try {
            logger.info("Initializing Data API feature for Velocity...");

            // Create connection adapter
            connectionAdapter = new VelocityConnectionAdapter(dataDirectory, logger);
            connection = connectionAdapter.createAdapter();

            // Create DataAPI instance
            dataAPI = DataAPI.create(connection);

            // Register with ServiceLocator
            serviceLocator.register(DataAPI.class, dataAPI);
            serviceLocator.register(ConnectionAdapter.class, connection);

            postgresAdapter = connectionAdapter.getPostgresAdapter();
            if (postgresAdapter != null) {
                serviceLocator.register(PostgresConnectionAdapter.class, postgresAdapter);
            } else {
                logger.warn("PostgreSQL adapter not available; relational features relying on SQL will be disabled.");
            }

            mongoAdapter = connectionAdapter.getMongoAdapter();
            if (mongoAdapter != null) {
                serviceLocator.register(MongoConnectionAdapter.class, mongoAdapter);
            } else {
                logger.warn("MongoDB adapter not available; document persistence will be disabled.");
            }

            logger.info("Data API initialized successfully for Velocity");

        } catch (Exception e) {
            logger.error("Failed to initialize Data API", e);
            throw new RuntimeException("Failed to initialize Data API", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data API feature...");

        if (connectionAdapter != null) {
            connectionAdapter.shutdown();
        }

        if (serviceLocator != null) {
            serviceLocator.unregister(DataAPI.class);
            serviceLocator.unregister(ConnectionAdapter.class);
            serviceLocator.unregister(PostgresConnectionAdapter.class);
            serviceLocator.unregister(MongoConnectionAdapter.class);
        }

        connection = null;
        dataAPI = null;
        postgresAdapter = null;
        mongoAdapter = null;
        connectionAdapter = null;

        logger.info("Data API shut down successfully");
    }

    @Override
    public int getPriority() {
        return 20; // High priority - initialize early as other features may depend on it
    }

    /**
     * Get the DataAPI instance
     *
     * @return the DataAPI instance
     */
    public DataAPI getDataAPI() {
        return dataAPI;
    }
}
