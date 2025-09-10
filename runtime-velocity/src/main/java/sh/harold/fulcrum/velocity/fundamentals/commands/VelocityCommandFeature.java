package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

/**
 * Feature that registers and manages Velocity proxy commands.
 */
public class VelocityCommandFeature implements VelocityFeature {
    
    private ProxyServer proxy;
    private Logger logger;
    private CommandManager commandManager;
    private ServiceLocator serviceLocator;
    
    @Override
    public String getName() {
        return "VelocityCommands";
    }
    
    @Override
    public int getPriority() {
        return 100; // After most other features are loaded
    }
    
    @Override
    public String[] getDependencies() {
        return new String[] { "DataAPI" }; // Depend on DataAPI for rank checking
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;
        
        // Get required services
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.commandManager = proxy.getCommandManager();
        
        FulcrumVelocityPlugin plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        
        // Register commands (currently no commands to register after limbo removal)
        
        logger.info("VelocityCommandFeature initialized");
    }
    
    
    @Override
    public void shutdown() {
        // No commands to unregister currently
        logger.info("VelocityCommandFeature shut down");
    }
}