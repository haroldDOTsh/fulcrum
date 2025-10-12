package sh.harold.fulcrum.api.menu;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Primary API service for the Fulcrum menu system.
 * Provides factory methods for creating menus and managing menu interactions.
 * <p>
 * This service follows the Fulcrum service interface pattern and should be
 * obtained through dependency injection or service registry.
 */
public interface MenuService {

    /**
     * Creates a new list menu builder for building paginated menus.
     * List menus automatically handle pagination for large item sets.
     *
     * @return a new ListMenuBuilder instance
     */
    ListMenuBuilder createListMenu();

    /**
     * Creates a new custom menu builder for building menus with custom dimensions.
     * Custom menus support viewport-based layouts and flexible positioning.
     *
     * @return a new CustomMenuBuilder instance
     */
    CustomMenuBuilder createMenuBuilder();

    /**
     * Opens a menu for the specified player.
     * This method handles all necessary setup including registering event handlers.
     *
     * @param menu   the menu to open
     * @param player the player to open the menu for
     * @return a CompletableFuture that completes when the menu is opened
     */
    CompletableFuture<Void> openMenu(Menu menu, Player player);

    /**
     * Closes the currently open menu for the specified player.
     *
     * @param player the player to close the menu for
     * @return true if a menu was closed, false if no menu was open
     */
    boolean closeMenu(Player player);

    /**
     * Closes all open menus for all players.
     * Useful for plugin shutdown or reload scenarios.
     *
     * @return the number of menus that were closed
     */
    int closeAllMenus();

    /**
     * Gets the currently open menu for a player.
     *
     * @param player the player to check
     * @return an Optional containing the menu if one is open, or empty if not
     */
    Optional<Menu> getOpenMenu(Player player);

    /**
     * Checks if a player has a menu open.
     *
     * @param player the player to check
     * @return true if the player has a menu open, false otherwise
     */
    boolean hasMenuOpen(Player player);


    /**
     * Refreshes the current menu for a player by re-rendering its contents.
     * Useful for updating dynamic content without closing and reopening.
     *
     * @param player the player to refresh the menu for
     * @return true if the menu was refreshed, false if no menu was open
     */
    boolean refreshMenu(Player player);

    /**
     * Gets the menu registry for managing menu templates and definitions.
     *
     * @return the menu registry instance
     */
    MenuRegistry getMenuRegistry();

    /**
     * Registers a plugin that will be using the menu service.
     * This is used for cleanup when plugins are disabled.
     *
     * @param plugin the plugin to register
     */
    void registerPlugin(Plugin plugin);

    /**
     * Unregisters a plugin and closes all menus created by that plugin.
     *
     * @param plugin the plugin to unregister
     */
    void unregisterPlugin(Plugin plugin);

    /**
     * Gets the total number of currently open menus across all players.
     *
     * @return the number of open menus
     */
    int getOpenMenuCount();

    /**
     * Registers a menu instance with a unique identifier.
     * This allows the menu to be retrieved and opened later using the menuId.
     *
     * @param menuId The unique identifier for the menu
     * @param menu   The menu instance to register
     */
    void registerMenuInstance(String menuId, Menu menu);

    /**
     * Gets a previously registered menu instance by its identifier.
     *
     * @param menuId The unique identifier of the menu
     * @return An Optional containing the menu if found, empty otherwise
     */
    Optional<Menu> getMenuInstance(String menuId);

    /**
     * Checks if a menu instance exists with the given identifier.
     *
     * @param menuId The unique identifier to check
     * @return true if a menu is registered with this id, false otherwise
     */
    boolean hasMenuInstance(String menuId);

    /**
     * Opens a previously registered menu instance for a player.
     *
     * @param menuId The unique identifier of the menu to open
     * @param player The player to open the menu for
     * @return A CompletableFuture that completes when the menu is opened,
     * or completes exceptionally if the menu is not found
     */
    CompletableFuture<Void> openMenuInstance(String menuId, Player player);

    /**
     * Checks if a menu template is registered in the menu registry.
     * This is a convenience method that delegates to the menu registry.
     *
     * @param templateId The template ID to check
     * @return true if the template exists in the registry
     */
    boolean hasMenuTemplate(String templateId);

    /**
     * Opens a menu from a template in the menu registry.
     * This is a convenience method that delegates to the menu registry.
     *
     * @param templateId The template ID
     * @param player     The player to open the menu for
     * @return CompletableFuture that completes when the menu is opened
     */
    CompletableFuture<Menu> openMenuTemplate(String templateId, Player player);

}