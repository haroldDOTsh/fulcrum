package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.menu.component.MenuButton;

import java.util.List;
import java.util.Optional;
import java.util.Stack;

/**
 * Service for managing menu navigation history and breadcrumbs.
 * Provides functionality for navigating between parent and child menus,
 * maintaining navigation history, and building breadcrumb titles.
 */
public interface NavigationService {
    
    /**
     * Pushes a menu onto the navigation stack for a player.
     * Used when opening a child menu from a parent.
     * 
     * @param player the player
     * @param menu the menu to push
     */
    void pushMenu(Player player, Menu menu);
    
    /**
     * Pops the current menu from the navigation stack and returns the previous.
     * Used for "back" navigation.
     * 
     * @param player the player
     * @return an Optional containing the previous menu, or empty if at root
     */
    Optional<Menu> popMenu(Player player);
    
    /**
     * Gets the current menu for a player without removing it.
     * 
     * @param player the player
     * @return an Optional containing the current menu
     */
    Optional<Menu> getCurrentMenu(Player player);
    
    /**
     * Gets the navigation history for a player.
     * The stack is ordered from oldest (bottom) to newest (top).
     * 
     * @param player the player
     * @return the navigation stack (unmodifiable)
     */
    Stack<Menu> getNavigationHistory(Player player);
    
    /**
     * Clears the navigation history for a player.
     * 
     * @param player the player
     */
    void clearHistory(Player player);
    
    /**
     * Clears all navigation history for all players.
     * Useful for plugin shutdown or reload.
     */
    void clearAllHistory();
    
    /**
     * Gets the depth of navigation for a player.
     * Root menu has depth 0.
     * 
     * @param player the player
     * @return the navigation depth
     */
    int getNavigationDepth(Player player);
    
    /**
     * Checks if a player can navigate back.
     * 
     * @param player the player
     * @return true if there's a previous menu in history
     */
    boolean canNavigateBack(Player player);
    
    /**
     * Builds a breadcrumb title showing the navigation path.
     * Example: "Main Menu > Shop > Weapons"
     * 
     * @param player the player
     * @return the breadcrumb component
     */
    Component buildBreadcrumb(Player player);
    
    /**
     * Builds a breadcrumb with a custom separator.
     * 
     * @param player the player
     * @param separator the separator component
     * @return the breadcrumb component
     */
    Component buildBreadcrumb(Player player, Component separator);
    
    /**
     * Builds a breadcrumb with maximum depth.
     * Shows "..." for truncated items.
     * 
     * @param player the player
     * @param maxDepth maximum number of items to show
     * @return the breadcrumb component
     */
    Component buildBreadcrumb(Player player, int maxDepth);
    
    /**
     * Gets the navigation path as a list of menu titles.
     * 
     * @param player the player
     * @return list of menu titles in navigation order
     */
    List<Component> getNavigationPath(Player player);
    
    /**
     * Navigates to the root menu (clears history except first).
     * 
     * @param player the player
     * @return the root menu if it exists
     */
    Optional<Menu> navigateToRoot(Player player);
    
    /**
     * Navigates to a specific depth in the history.
     * Depth 0 is the root menu.
     * 
     * @param player the player
     * @param depth the target depth
     * @return the menu at that depth if it exists
     */
    Optional<Menu> navigateToDepth(Player player, int depth);
    
    /**
     * Registers a navigation listener for menu navigation events.
     * 
     * @param listener the navigation listener
     */
    void addNavigationListener(NavigationListener listener);
    
    /**
     * Removes a navigation listener.
     * 
     * @param listener the listener to remove
     */
    void removeNavigationListener(NavigationListener listener);
    
    /**
     * Sets the default back button to use for child menus.
     * This button will be automatically added when opening child menus.
     * 
     * @param backButton the default back button
     * @param slot the slot to place it in
     */
    void setDefaultBackButton(MenuButton backButton, int slot);
    
    /**
     * Gets the default back button configuration.
     * 
     * @return an Optional containing the back button and slot
     */
    Optional<BackButtonConfig> getDefaultBackButton();
    
    /**
     * Configuration for the default back button.
     */
    interface BackButtonConfig {
        /**
         * Gets the back button.
         * 
         * @return the button
         */
        MenuButton getButton();
        
        /**
         * Gets the slot position.
         * 
         * @return the slot
         */
        int getSlot();
    }
    
    /**
     * Listener interface for navigation events.
     */
    interface NavigationListener {
        /**
         * Called when a player navigates forward (opens child menu).
         * 
         * @param player the player
         * @param from the parent menu
         * @param to the child menu
         */
        default void onNavigateForward(Player player, Menu from, Menu to) {}
        
        /**
         * Called when a player navigates back.
         * 
         * @param player the player
         * @param from the current menu
         * @param to the previous menu
         */
        default void onNavigateBack(Player player, Menu from, Menu to) {}
        
        /**
         * Called when navigation history is cleared.
         * 
         * @param player the player
         */
        default void onHistoryCleared(Player player) {}
    }
}