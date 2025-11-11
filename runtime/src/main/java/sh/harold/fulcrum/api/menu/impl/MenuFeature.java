package sh.harold.fulcrum.api.menu.impl;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.menu.MenuRegistry;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.util.Optional;

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

        container.getOptional(CooldownRegistry.class)
                .or(() -> Optional.ofNullable(sh.harold.fulcrum.lifecycle.ServiceLocatorImpl.getInstance())
                        .flatMap(locator -> locator.findService(CooldownRegistry.class)))
                .ifPresent(MenuButton::bindCooldownRegistry);

        // Register MenuService with Bukkit's ServicesManager for access from other components
        plugin.getServer().getServicesManager().register(
                MenuService.class, menuService, plugin, ServicePriority.Normal
        );

        // Create and register inventory listener
        inventoryListener = new MenuInventoryListener(menuService, plugin);
        plugin.getServer().getPluginManager().registerEvents(inventoryListener, plugin);

        plugin.getLogger().info("[MENU] Initialized successfully");
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

            System.out.println("[MENU] Shutdown complete");
        }
        MenuButton.clearCooldownRegistry();
    }

    @Override
    public int getPriority() {
        return 25; // Load after message-api (priority 1) but before other features
    }
}
