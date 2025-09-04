package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.commands.LimboCommand;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

/**
 * Feature that registers and manages Velocity proxy commands.
 */
public class VelocityCommandFeature implements VelocityFeature {
    
    private ProxyServer proxy;
    private Logger logger;
    private CommandManager commandManager;
    
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
        return new String[] { "VelocityServerLifecycle", "DataAPI" };
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        
        // Get required services
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.commandManager = proxy.getCommandManager();
        
        DataAPI dataAPI = serviceLocator.getRequiredService(DataAPI.class);
        VelocityServerLifecycleFeature lifecycleFeature = serviceLocator.getRequiredService(VelocityServerLifecycleFeature.class);
        FulcrumVelocityPlugin plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        
        // Register commands
        registerLimboCommand(dataAPI, lifecycleFeature, plugin);
        
        logger.info("VelocityCommandFeature initialized - commands registered");
    }
    
    /**
     * Register the /limbo command
     */
    private void registerLimboCommand(DataAPI dataAPI, VelocityServerLifecycleFeature lifecycleFeature, 
                                     FulcrumVelocityPlugin plugin) {
        try {
            // Create the limbo command instance
            LimboCommand limboCommand = new LimboCommand(proxy, logger, dataAPI, lifecycleFeature);
            
            // Build command meta with aliases
            CommandMeta commandMeta = commandManager.metaBuilder("limbo")
                .aliases("l", "hub", "lobby")  // Additional aliases for convenience
                .plugin(plugin)
                .build();
            
            // Register the command
            commandManager.register(commandMeta, limboCommand);
            
            logger.info("Registered /limbo command with aliases: l, hub, lobby");
            
        } catch (Exception e) {
            logger.error("Failed to register /limbo command", e);
            throw new RuntimeException("Failed to register /limbo command", e);
        }
    }
    
    @Override
    public void shutdown() {
        // Unregister commands if needed
        try {
            commandManager.unregister("limbo");
            logger.info("Unregistered /limbo command");
        } catch (Exception e) {
            logger.warn("Error unregistering commands during shutdown", e);
        }
        
        logger.info("VelocityCommandFeature shut down");
    }
}