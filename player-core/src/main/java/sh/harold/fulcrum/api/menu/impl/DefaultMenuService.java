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
 * Simplified implementation of MenuService focusing on basic menu functionality.
 * This implementation removes the complex navigation system in favor of the new
 * parentMenu() builder approach for handling menu relationships.
 */
public class DefaultMenuService implements MenuService {
    
    private final Plugin plugin;
    private final MenuRegistry menuRegistry;
    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<String>> pluginMenus = new ConcurrentHashMap<>();
    
    // NEW: Menu instance registry for ID-based menu storage and retrieval
    private final Map<String, Menu> menuInstances = new ConcurrentHashMap<>();
    
    public DefaultMenuService(Plugin plugin, MenuRegistry menuRegistry) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.menuRegistry = Objects.requireNonNull(menuRegistry, "MenuRegistry cannot be null");
        
        System.out.println("[MENU SERVICE] DefaultMenuService initialized (simplified version)");
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
            try {
                System.out.println("[MENU SERVICE] Opening menu: " + menu.getTitle() + " for " + player.getName());
                
                // Track the menu
                openMenus.put(player.getUniqueId(), menu);
                trackMenuForPlugin(menu);
                
                // Open the menu on the main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.openInventory(menu.getInventory());
                    menu.update();
                });
                
                System.out.println("[MENU SERVICE] Successfully opened menu: " + menu.getTitle());
                
            } catch (Exception e) {
                System.err.println("[MENU SERVICE] Error opening menu: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    @Override
    public boolean closeMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        
        Menu menu = openMenus.remove(player.getUniqueId());
        if (menu != null) {
            Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
            System.out.println("[MENU SERVICE] Closed menu for " + player.getName());
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
    public boolean refreshMenu(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        Menu menu = openMenus.get(player.getUniqueId());
        if (menu != null) {
            menu.update();
            return true;
        }
        return false;
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
            // Close all menus for all players
            int closed = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                Menu currentMenu = openMenus.get(player.getUniqueId());
                if (currentMenu != null && currentMenu.getOwnerPlugin().equals(plugin)) {
                    closeMenu(player);
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
     * Registers a menu instance with a custom ID for later retrieval.
     * This allows any menu to be referenced by children via .parentMenu(id).
     *
     * @param menuId the custom menu ID
     * @param menu the menu instance to register
     */
    public void registerMenuInstance(String menuId, Menu menu) {
        Objects.requireNonNull(menuId, "Menu ID cannot be null");
        Objects.requireNonNull(menu, "Menu cannot be null");
        
        menuInstances.put(menuId, menu);
        System.out.println("[MENU SERVICE] Registered menu instance: " + menuId);
    }
    
    /**
     * Gets a menu instance by its custom ID.
     *
     * @param menuId the menu ID
     * @return the menu instance, or null if not found
     */
    public Menu getMenuInstance(String menuId) {
        return menuInstances.get(menuId);
    }
    
    /**
     * Checks if a menu instance is registered with the given ID.
     *
     * @param menuId the menu ID
     * @return true if the menu instance exists
     */
    public boolean hasMenuInstance(String menuId) {
        return menuInstances.containsKey(menuId);
    }
    
    /**
     * Opens a registered menu instance by ID.
     *
     * @param menuId the menu ID
     * @param player the player to open the menu for
     * @return CompletableFuture that completes when the menu is opened
     */
    public CompletableFuture<Void> openMenuInstance(String menuId, Player player) {
        Menu menu = getMenuInstance(menuId);
        if (menu == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Menu instance not found: " + menuId));
        }
        return openMenu(menu, player);
    }
    
    /**
     * Shuts down the menu service.
     */
    public void shutdown() {
        // Close all menus
        closeAllMenus();
        
        // Clear all plugin registrations
        pluginMenus.clear();
        openMenus.clear();
        
        // Clear registry
        menuRegistry.clearRegistry();
        
        System.out.println("[MENU SERVICE] MenuService shutdown complete");
    }
    
    /**
     * Removes a menu from tracking when closed externally.
     * Called by inventory close event handler.
     *
     * @param player the player whose menu closed
     */
    public void handleMenuClosed(Player player) {
        openMenus.remove(player.getUniqueId());
        System.out.println("[MENU SERVICE] External menu close handled for " + player.getName());
    }
    
    /**
     * Tracks a menu for plugin ownership.
     *
     * @param menu the menu to track
     */
    private void trackMenuForPlugin(Menu menu) {
        Plugin owner = menu.getOwnerPlugin();
        if (owner != null) {
            pluginMenus.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet())
                .add(menu.getId());
        }
    }
}