package sh.harold.fulcrum.api.menu;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

/**
 * Feature implementation for the menu system.
 * This feature initializes all menu-related services and integrates them with the Fulcrum lifecycle.
 * 
 * <p>The menu feature provides a complete menu system including:
 * - Menu service for creating and managing menus
 * - Menu registry for storing menu templates
 * - Player menu manager for tracking player-specific menu state
 * - Security validator for validating menu operations
 * - Event listener for handling inventory interactions
 */
public class MenuFeature implements PluginFeature {


    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
 
    }

    @Override
    public void shutdown() {

    }
    
    @Override
    public int getPriority() {
        return 25; // Load after message-api (priority 1) but before other features
    }
}