package sh.harold.fulcrum.velocity.fundamentals.data;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;

public class VelocityDataAPIFeature implements VelocityFeature {
    private Logger logger;
    private VelocityConnectionAdapter connectionAdapter;
    private DataAPI dataAPI;
    private ProxyServer proxy;
    private Path dataDirectory;
    
    @Override
    public String getName() {
        return "DataAPI";
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        
        // Get proxy server and data directory from service locator
        this.proxy = serviceLocator.getService(ProxyServer.class);
        this.dataDirectory = serviceLocator.getService(Path.class);
        
        try {
            logger.info("Initializing Data API feature for Velocity...");
            
            // Create connection adapter
            connectionAdapter = new VelocityConnectionAdapter(dataDirectory, logger);
            ConnectionAdapter adapter = connectionAdapter.createAdapter();
            
            // Create DataAPI instance
            dataAPI = DataAPI.create(adapter);
            
            // Register with ServiceLocator
            serviceLocator.registerService(DataAPI.class, dataAPI);
            serviceLocator.registerService(ConnectionAdapter.class, adapter);
            
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
        
        logger.info("Data API shut down successfully");
    }
    
    @Override
    public int getPriority() {
        return 100; // High priority - initialize early as other features may depend on it
    }
    
    @Override
    public boolean isFundamental() {
        return true; // This is a fundamental feature
    }
    
    /**
     * Get the DataAPI instance
     * @return the DataAPI instance
     */
    public DataAPI getDataAPI() {
        return dataAPI;
    }
}