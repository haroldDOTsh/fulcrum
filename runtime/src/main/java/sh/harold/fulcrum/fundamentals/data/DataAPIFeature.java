package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataApiCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityCommandPort;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.module.ServiceLocator;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.util.logging.Logger;

public class DataAPIFeature implements PluginFeature {
    private JavaPlugin plugin;
    private Logger logger;
    private FulcrumConnectionAdapter connectionAdapter;
    private DataAPI dataAPI;
    private DataAuthority.CommandPort commandPort;
    private PaperRuntime runtime;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        
        try {
            logger.info("Initializing Data API feature...");

            runtime = container.get(PaperRuntime.class);
            
            // Create connection adapter
            connectionAdapter = new FulcrumConnectionAdapter(plugin);
            ConnectionAdapter adapter = connectionAdapter.createAdapter();
            
            // Create DataAPI instance
            dataAPI = DataAPI.create(adapter, runtime.asyncExecutor());
            commandPort = adapter instanceof PostgresConnectionAdapter postgresAdapter
                ? new PostgresAuthorityCommandPort(postgresAdapter, runtime.asyncExecutor())
                : new DataApiCommandPort(dataAPI);
            
            // Register with both DependencyContainer and ServiceLocator
            container.register(DataAPI.class, dataAPI);
            container.register(ConnectionAdapter.class, adapter);
            container.register(DataAuthority.CommandPort.class, commandPort);
            
            // Also register via ServiceLocator if available
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(DataAPI.class, dataAPI);
                ServiceLocatorImpl.getInstance().registerService(ConnectionAdapter.class, adapter);
                ServiceLocatorImpl.getInstance().registerService(DataAuthority.CommandPort.class, commandPort);
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
        
        if (connectionAdapter != null) {
            connectionAdapter.shutdown();
        }
        
        // Unregister services
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(DataAPI.class);
            ServiceLocatorImpl.getInstance().unregisterService(ConnectionAdapter.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.CommandPort.class);
        }
        
        logger.info("Data API shut down successfully");
    }
    
    @Override
    public int getPriority() {
        return 10; // Initialize early as other features may depend on it
    }

    @Override
    public Class<?>[] getDependencies() {
        return new Class<?>[] { PaperRuntime.class };
    }
    
    /**
     * Get the DataAPI instance
     * @return the DataAPI instance
     */
    public DataAPI getDataAPI() {
        return dataAPI;
    }
}
