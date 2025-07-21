package sh.harold.fulcrum.api.menu;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.menu.impl.*;
import sh.harold.fulcrum.api.menu.test.MenuDemoCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

/**
 * Simplified MenuFeature that initializes the menu system without complex navigation services.
 * This version focuses on basic menu functionality with the new parentMenu() builder approach.
 */
public class MenuFeature implements PluginFeature {
    
    private MenuInventoryListener inventoryListener;
    private DefaultMenuService menuService;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Create menu registry
        DefaultMenuRegistry menuRegistry = new DefaultMenuRegistry();
        container.register(MenuRegistry.class, menuRegistry);
        
        // Create simplified menu service
        menuService = new DefaultMenuService(plugin, menuRegistry);
        container.register(MenuService.class, menuService);
        
        // Register MenuService with Bukkit's ServicesManager for access from other components
        plugin.getServer().getServicesManager().register(
            MenuService.class, menuService, plugin, ServicePriority.Normal
        );
        
        // Create and register inventory listener
        inventoryListener = new MenuInventoryListener(menuService, plugin);
        plugin.getServer().getPluginManager().registerEvents(inventoryListener, plugin);
        
        // Register MenuDemoCommand
        MenuDemoCommand menuDemoCommand = new MenuDemoCommand(menuService);
        CommandRegistrar.register(menuDemoCommand.build());

        System.out.println("[MENU FEATURE] MenuFeature initialized (simplified version)");
    }

    @Override
    public void shutdown() {
        if (menuService != null) {
            // Close all open menus
            int closed = menuService.closeAllMenus();
            if (closed > 0) {
                menuService.getPlugin().getLogger().info("Closed " + closed + " open menus during shutdown");
            }
            
            // Shutdown the menu service
            menuService.shutdown();
            
            System.out.println("[MENU FEATURE] MenuFeature shutdown complete");
        }
    }
    
    @Override
    public int getPriority() {
        return 25; // Load after message-api (priority 1) but before other features
    }
}