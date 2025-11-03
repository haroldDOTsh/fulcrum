package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

public class DataAPIFeature implements PluginFeature {
    private JavaPlugin plugin;
    private Logger logger;
    private FulcrumConnectionAdapter connectionAdapter;
    private DependencyContainer container;
    private PostgresConnectionAdapter postgresAdapter;
    private MongoConnectionAdapter mongoAdapter;
    private DataAPI dataAPI;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;

        try {
            logger.info("Initializing Data API feature...");

            // Create connection adapter
            connectionAdapter = new FulcrumConnectionAdapter(plugin);
            ConnectionAdapter adapter = connectionAdapter.createAdapter();

            // Create DataAPI instance
            dataAPI = DataAPI.create(adapter);

            // Register with both DependencyContainer and ServiceLocator
            container.register(DataAPI.class, dataAPI);
            container.register(ConnectionAdapter.class, adapter);

            // Register Postgres adapter if available
            postgresAdapter = connectionAdapter.getPostgresAdapter();
            if (postgresAdapter != null) {
                container.register(PostgresConnectionAdapter.class, postgresAdapter);
                if (ServiceLocatorImpl.getInstance() != null) {
                    ServiceLocatorImpl.getInstance().registerService(PostgresConnectionAdapter.class, postgresAdapter);
                }
            } else {
                logger.warning("PostgreSQL adapter not available; relational features relying on SQL will be disabled.");
            }

            mongoAdapter = connectionAdapter.getMongoAdapter();
            if (mongoAdapter != null) {
                container.register(MongoConnectionAdapter.class, mongoAdapter);
                if (ServiceLocatorImpl.getInstance() != null) {
                    ServiceLocatorImpl.getInstance().registerService(MongoConnectionAdapter.class, mongoAdapter);
                }
            } else {
                logger.warning("MongoDB adapter not available; minigame data persistence will be disabled.");
            }

            // Also register via ServiceLocator if available
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(DataAPI.class, dataAPI);
                ServiceLocatorImpl.getInstance().registerService(ConnectionAdapter.class, adapter);
            }

            logger.info("Data API initialized successfully");

        } catch (Exception e) {
            logger.severe("Failed to initialize Data API: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Data API", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data API feature...");

        if (dataAPI != null) {
            dataAPI.shutdown();
            dataAPI = null;
        }

        if (connectionAdapter != null) {
            connectionAdapter.shutdown();
        }

        // Unregister services
        if (container != null) {
            container.unregister(DataAPI.class);
            container.unregister(ConnectionAdapter.class);
            container.unregister(PostgresConnectionAdapter.class);
            container.unregister(MongoConnectionAdapter.class);
        }

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(DataAPI.class);
            ServiceLocatorImpl.getInstance().unregisterService(ConnectionAdapter.class);
            ServiceLocatorImpl.getInstance().unregisterService(PostgresConnectionAdapter.class);
            ServiceLocatorImpl.getInstance().unregisterService(MongoConnectionAdapter.class);
        }

        logger.info("Data API shut down successfully");
    }

    @Override
    public int getPriority() {
        return 10; // Initialize early as other features may depend on it
    }

    /**
     * Get the DataAPI instance
     *
     * @return the DataAPI instance
     */
    public DataAPI getDataAPI() {
        return dataAPI;
    }

    public PostgresConnectionAdapter getPostgresAdapter() {
        return postgresAdapter;
    }

    public MongoConnectionAdapter getMongoAdapter() {
        return mongoAdapter;
    }
}
