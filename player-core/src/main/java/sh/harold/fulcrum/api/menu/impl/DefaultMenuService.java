package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of MenuService.
 * Manages menu lifecycle, navigation, and plugin registrations.
 */
public class DefaultMenuService implements MenuService {
    
    private final Plugin plugin;
    private final NavigationService navigationService;
    private final MenuRegistry menuRegistry;
    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<String>> pluginMenus = new ConcurrentHashMap<>();
    
    public DefaultMenuService(Plugin plugin, NavigationService navigationService, MenuRegistry menuRegistry) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.navigationService = Objects.requireNonNull(navigationService, "NavigationService cannot be null");
        this.menuRegistry = Objects.requireNonNull(menuRegistry, "MenuRegistry cannot be null");
    }
    
    @Override
    public ListMenuBuilder createListMenu() {
        return new DefaultListMenuBuilder(this);
    }
    
    @Override
    public CustomMenuBuilder createMenuBuilder() {
        return new DefaultCustomMenuBuilder(this);
    }
    
    @Override
    public CompletableFuture<Void> openMenu(Menu menu, Player player) {
        Objects.requireNonNull(menu, "Menu cannot be null");
        Objects.requireNonNull(player, "Player cannot be null");
        
        return CompletableFuture.runAsync(() -> {
            // Close existing menu if any
            closeMenu(player);
            
            // Store the new menu
            openMenus.put(player.getUniqueId(), menu);
            
            // Push to navigation stack
            navigationService.pushMenu(player, menu);
            
            // Track plugin ownership
            trackMenuForPlugin(menu);
            
            // Open the inventory on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.openInventory(menu.getInventory());
            });
        });
    }
    
    @Override
    public boolean closeMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Menu menu = openMenus.remove(player.getUniqueId());
        if (menu != null) {
            // Run close handlers
            if (menu instanceof AbstractMenu) {
                ((AbstractMenu) menu).triggerCloseHandlers();
            }
            
            // Close inventory on main thread if needed
            if (player.getOpenInventory().getTopInventory().equals(menu.getInventory())) {
                Bukkit.getScheduler().runTask(plugin, (Runnable) player::closeInventory);
            }
            
            return true;
        }
        return false;
    }
    
    @Override
    public int closeAllMenus() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (closeMenu(player)) {
                count++;
            }
        }
        return count;
    }
    
    @Override
    public Optional<Menu> getOpenMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        return Optional.ofNullable(openMenus.get(player.getUniqueId()));
    }
    
    @Override
    public boolean hasMenuOpen(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        return openMenus.containsKey(player.getUniqueId());
    }
    
    @Override
    public boolean navigateBack(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Optional<Menu> previousMenu = navigationService.popMenu(player);
        if (previousMenu.isPresent()) {
            return openMenu(previousMenu.get(), player)
                .thenApply(v -> true)
                .exceptionally(ex -> false)
                .join();
        }
        
        // No previous menu, just close current
        return closeMenu(player);
    }
    
    @Override
    public void clearNavigationHistory(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        navigationService.clearHistory(player);
    }
    
    @Override
    public CompletableFuture<Void> openChildMenu(Menu childMenu, Player player, Component parentTitle) {
        Objects.requireNonNull(childMenu, "Child menu cannot be null");
        Objects.requireNonNull(player, "Player cannot be null");
        
        // Set parent relationship
        getOpenMenu(player).ifPresent(childMenu::setParent);
        
        // Add default back button if configured
        navigationService.getDefaultBackButton().ifPresent(config -> {
            if (childMenu instanceof AbstractMenu) {
                AbstractMenu abstractMenu = (AbstractMenu) childMenu;
                abstractMenu.setButton(config.getButton(), config.getSlot());
            }
        });
        
        return openMenu(childMenu, player);
    }
    
    @Override
    public boolean refreshMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        return getOpenMenu(player).map(menu -> {
            menu.update();
            return true;
        }).orElse(false);
    }
    
    @Override
    public NavigationService getNavigationService() {
        return navigationService;
    }
    
    @Override
    public MenuRegistry getMenuRegistry() {
        return menuRegistry;
    }
    
    @Override
    public void registerPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        pluginMenus.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet());
    }
    
    @Override
    public void unregisterPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        Set<String> menuIds = pluginMenus.remove(plugin);
        if (menuIds != null) {
            // Close all menus created by this plugin
            int closed = 0;
            Iterator<Map.Entry<UUID, Menu>> iterator = openMenus.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Menu> entry = iterator.next();
                if (entry.getValue().getOwnerPlugin().equals(plugin)) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null) {
                        closeMenu(player);
                    } else {
                        iterator.remove();
                    }
                    closed++;
                }
            }
            
            if (closed > 0) {
                plugin.getLogger().info("Closed " + closed + " menus during plugin unregister");
            }
        }
        
        // Also unregister from menu registry
        menuRegistry.unregisterTemplates(plugin);
    }
    
    @Override
    public int getOpenMenuCount() {
        return openMenus.size();
    }
    
    /**
     * Gets the main plugin instance.
     * 
     * @return the plugin
     */
    public Plugin getPlugin() {
        return plugin;
    }
    
    /**
     * Shuts down the menu service.
     */
    public void shutdown() {
        // Close all menus
        closeAllMenus();
        
        // Clear all plugin registrations
        pluginMenus.clear();
        
        // Clear registry
        menuRegistry.clearRegistry();
    }
    
    /**
     * Removes a menu from the open menus map.
     * Called by inventory close event handler.
     * 
     * @param player the player whose menu closed
     */
    public void handleMenuClosed(Player player) {
        openMenus.remove(player.getUniqueId());
    }
    
    private void trackMenuForPlugin(Menu menu) {
        Plugin owner = menu.getOwnerPlugin();
        if (owner != null) {
            pluginMenus.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet())
                .add(menu.getId());
        }
    }
}