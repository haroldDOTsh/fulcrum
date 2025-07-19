package sh.harold.fulcrum.api.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.player.PlayerMenuManager;
import sh.harold.fulcrum.api.menu.registry.MenuRegistry;
import sh.harold.fulcrum.api.menu.security.MenuSecurityContext;
import sh.harold.fulcrum.api.menu.types.ConfirmationMenu;
import sh.harold.fulcrum.api.menu.types.ListMenu;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main service interface for menu management.
 * Provides async operations for creating, managing, and displaying menus to players.
 * 
 * <p>This service follows the established Fulcrum pattern of async-first operations
 * and provides a clean API for menu interactions while maintaining security and
 * performance requirements.
 */
public interface MenuService {

    /**
     * Gets the menu registry for managing menu definitions.
     * 
     * @return the menu registry instance
     */
    MenuRegistry getMenuRegistry();

    /**
     * Gets the player menu manager for managing player-specific menu state.
     * 
     * @return the player menu manager instance
     */
    PlayerMenuManager getPlayerMenuManager();

    /**
     * Creates a new list menu builder for the specified player.
     * 
     * @param player the player who will interact with the menu
     * @param menuId unique identifier for this menu
     * @return a new list menu builder
     * @throws IllegalArgumentException if player is null or menuId is null/empty
     */
    ListMenu.Builder createListMenu(Player player, String menuId);

    /**
     * Creates a new confirmation menu builder for the specified player.
     *
     * @param player the player who will interact with the menu
     * @param menuId unique identifier for this menu
     * @return a new confirmation menu builder
     * @throws IllegalArgumentException if player is null or menuId is null/empty
     */
    ConfirmationMenu.Builder createConfirmationMenu(Player player, String menuId);

    /**
     * Creates a new generic menu builder for the specified player.
     * This builder supports the unified addItems() methods and provides full flexibility.
     *
     * @param player the player who will interact with the menu
     * @param menuId unique identifier for this menu
     * @return a new menu builder
     * @throws IllegalArgumentException if player is null or menuId is null/empty
     */
    MenuBuilder createMenuBuilder(Player player, String menuId);

    /**
     * Opens a menu for the specified player.
     * 
     * @param player the player to open the menu for
     * @param menu the menu to open
     * @return a CompletableFuture that completes when the menu is opened
     * @throws IllegalArgumentException if player or menu is null
     */
    CompletableFuture<Void> openMenu(Player player, Menu menu);

    /**
     * Closes the currently open menu for the specified player.
     * 
     * @param player the player to close the menu for
     * @return a CompletableFuture that completes when the menu is closed
     * @throws IllegalArgumentException if player is null
     */
    CompletableFuture<Void> closeMenu(Player player);

    /**
     * Gets the currently open menu for the specified player.
     * 
     * @param playerId the UUID of the player
     * @return the currently open menu, or null if no menu is open
     * @throws IllegalArgumentException if playerId is null
     */
    Menu getCurrentMenu(UUID playerId);

    /**
     * Checks if the specified player has a menu open.
     * 
     * @param playerId the UUID of the player
     * @return true if the player has a menu open, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasMenuOpen(UUID playerId);

    /**
     * Refreshes the currently open menu for the specified player.
     * This will update the menu contents without closing and reopening it.
     * 
     * @param player the player whose menu should be refreshed
     * @return a CompletableFuture that completes when the menu is refreshed
     * @throws IllegalArgumentException if player is null
     */
    CompletableFuture<Void> refreshMenu(Player player);

    /**
     * Handles a menu item click event.
     * This method is called internally by the menu system and should not be called directly.
     * 
     * @param player the player who clicked
     * @param menu the menu that was clicked
     * @param slot the slot that was clicked
     * @param clickedItem the item that was clicked
     * @param clickType the type of click performed
     * @return a CompletableFuture that completes when the click is handled
     */
    CompletableFuture<Void> handleMenuClick(Player player, Menu menu, int slot, ItemStack clickedItem, org.bukkit.event.inventory.ClickType clickType);

    /**
     * Creates a security context for menu operations.
     * This is used internally to track and validate menu operations.
     * 
     * @param player the player for whom to create the context
     * @return a new security context
     * @throws IllegalArgumentException if player is null
     */
    MenuSecurityContext createSecurityContext(Player player);

    /**
     * Validates that a menu operation is allowed for the specified player.
     * 
     * @param player the player attempting the operation
     * @param menu the menu being operated on
     * @param operation the operation being performed
     * @return true if the operation is allowed, false otherwise
     * @throws IllegalArgumentException if any parameter is null
     */
    boolean isOperationAllowed(Player player, Menu menu, String operation);

    /**
     * Closes all menus for all players.
     * This is typically called during plugin shutdown.
     * 
     * @return a CompletableFuture that completes when all menus are closed
     */
    CompletableFuture<Void> closeAllMenus();

    /**
     * Gets the number of players currently with menus open.
     * 
     * @return the number of players with open menus
     */
    int getActiveMenuCount();

    /**
     * Cleans up expired menu sessions and performs maintenance tasks.
     * This method should be called periodically by the implementation.
     * 
     * @return a CompletableFuture that completes when cleanup is finished
     */
    CompletableFuture<Void> performMaintenance();
}