package sh.harold.fulcrum.api.chat.impl;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

/**
 * Feature module for chat formatting.
 * Integrates with Paper's AsyncChatEvent to format player chat messages.
 */
public class ChatFormatFeature implements PluginFeature, Listener {
    
    private JavaPlugin plugin;
    private Logger logger;
    private ChatFormatService chatFormatService;
    private DependencyContainer container;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.container = container;
        
        try {
            // Get required services
            RankService rankService = container.get(RankService.class);
            if (rankService == null) {
                logger.warning("RankService not available, chat formatting will not work properly!");
                return;
            }
            
            // Create default formatter
            DefaultChatFormatter defaultFormatter = new DefaultChatFormatter(rankService);
            
            // Create and register service
            chatFormatService = new DefaultChatFormatService(defaultFormatter);
            container.register(ChatFormatService.class, chatFormatService);
            
            // Also register via ServiceLocator if available
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(ChatFormatService.class, chatFormatService);
            }
            
            // Register event listener
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            
            logger.info("Chat formatting enabled with Hypixel-style format");
        } catch (Exception e) {
            logger.severe("Failed to initialize chat formatting: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down chat formatting...");
        
        // Unregister service
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(ChatFormatService.class);
        }
        
        logger.info("Chat formatting shut down");
    }
    
    @Override
    public int getPriority() {
        return 60; // After rank system (50)
    }
    
    /**
     * Handles async chat events to format messages.
     * Uses HIGH priority to format after other plugins have processed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        if (chatFormatService == null) {
            return; // Service not initialized
        }
        
        try {
            // Format the message
            Component formatted = chatFormatService.formatMessage(
                event.getPlayer(), 
                event.originalMessage()
            );
            
            // Set the renderer to always return our formatted message
            event.renderer((source, sourceDisplayName, message, viewer) -> formatted);
        } catch (Exception e) {
            logger.warning("Error formatting chat message for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }
}
