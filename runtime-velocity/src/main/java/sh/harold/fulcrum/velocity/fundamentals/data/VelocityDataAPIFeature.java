package sh.harold.fulcrum.velocity.fundamentals.data;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataApiCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityCommandPort;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;

public class VelocityDataAPIFeature implements VelocityFeature {
    private Logger logger;
    private VelocityConnectionAdapter connectionAdapter;
    private DataAPI dataAPI;
    private DataAuthority.CommandPort commandPort;
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
            boolean developmentMode = serviceLocator.getService(ConfigLoader.class)
                .map(loader -> loader.get("development-mode", false))
                .orElse(false);
            connectionAdapter = new VelocityConnectionAdapter(dataDirectory, logger, developmentMode);
            ConnectionAdapter adapter = connectionAdapter.createAdapter();
            
            // Create DataAPI instance
            dataAPI = DataAPI.create(adapter);
            commandPort = adapter instanceof PostgresConnectionAdapter postgresAdapter
                ? new PostgresAuthorityCommandPort(postgresAdapter)
                : new DataApiCommandPort(dataAPI);
            
            // Register with ServiceLocator
            serviceLocator.register(DataAPI.class, dataAPI);
            serviceLocator.register(ConnectionAdapter.class, adapter);
            serviceLocator.register(DataAuthority.CommandPort.class, commandPort);
            
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
            serviceLocator.unregister(DataAuthority.CommandPort.class);
        }
        commandPort = null;
        
        logger.info("Data API shut down successfully");
    }
    
    @Override
    public int getPriority() {
        return 20; // High priority - initialize early as other features may depend on it
    }
    
    /**
     * Get the DataAPI instance
     * @return the DataAPI instance
     */
    public DataAPI getDataAPI() {
        return dataAPI;
    }
}
