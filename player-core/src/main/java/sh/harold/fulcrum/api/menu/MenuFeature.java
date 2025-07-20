package sh.harold.fulcrum.api.menu;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.menu.impl.*;
import sh.harold.fulcrum.api.menu.test.MenuDemoCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class MenuFeature implements PluginFeature {
    
    private MenuInventoryListener inventoryListener;
    private DefaultMenuService menuService;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Create navigation service first
        DefaultNavigationService navigationService = new DefaultNavigationService();
        container.register(NavigationService.class, navigationService);
        
        // Create menu registry
        DefaultMenuRegistry menuRegistry = new DefaultMenuRegistry();
        container.register(MenuRegistry.class, menuRegistry);
        
        // Create menu service with dependencies
        menuService = new DefaultMenuService(plugin, navigationService, menuRegistry);
        container.register(MenuService.class, menuService);
        
        // Create and register inventory listener
        inventoryListener = new MenuInventoryListener(menuService, plugin);
        plugin.getServer().getPluginManager().registerEvents(inventoryListener, plugin);
        
        // Register MenuDemoCommand
        MenuDemoCommand menuDemoCommand = new MenuDemoCommand(menuService);
        CommandRegistrar.register(menuDemoCommand.build());
    }

    @Override
    public void shutdown() {
        if (menuService != null) {
            // Close all open menus
            int closed = menuService.closeAllMenus();
            if (closed > 0) {
                menuService.getPlugin().getLogger().info("Closed " + closed + " open menus during shutdown");
            }
            
            // Clear navigation history
            menuService.getNavigationService().clearAllHistory();
            
            // Unregister all plugins from menu service
            menuService.shutdown();
        }
    }
    
    @Override
    public int getPriority() {
        return 25; // Load after message-api (priority 1) but before other features
    }
}