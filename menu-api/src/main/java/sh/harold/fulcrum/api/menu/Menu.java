package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Represents a menu instance that can be displayed to players.
 * This interface provides methods for managing menu state and content.
 */
public interface Menu {
    
    /**
     * Gets the unique identifier for this menu instance.
     * 
     * @return the menu's unique ID
     */
    String getId();
    
    /**
     * Gets the title of this menu.
     * 
     * @return the menu title as an Adventure Component
     */
    Component getTitle();
    
    /**
     * Gets the size of this menu in slots.
     * For chest inventories, this will be a multiple of 9 (9, 18, 27, 36, 45, or 54).
     * 
     * @return the number of slots in this menu
     */
    int getSize();
    
    /**
     * Gets the number of rows in this menu.
     * 
     * @return the number of rows (size / 9)
     */
    default int getRows() {
        return getSize() / 9;
    }
    
    /**
     * Gets the Bukkit inventory associated with this menu.
     * 
     * @return the inventory instance
     */
    Inventory getInventory();
    
    /**
     * Gets the plugin that created this menu.
     * 
     * @return the plugin instance
     */
    Plugin getOwnerPlugin();
    
    /**
     * Checks if this menu is currently open for any player.
     * 
     * @return true if the menu is open, false otherwise
     */
    boolean isOpen();
    
    /**
     * Gets the player viewing this menu.
     * 
     * @return an Optional containing the viewer if the menu is open, or empty if not
     */
    Optional<Player> getViewer();
    
    /**
     * Updates the menu contents for the current viewer.
     * This triggers a re-render of the menu without closing it.
     */
    void update();
    
    /**
     * Closes this menu for its current viewer.
     */
    void close();
    
    /**
     * Gets the menu context containing state and metadata.
     * 
     * @return the menu context
     */
    MenuContext getContext();
    
    /**
     * Gets a property value from the menu context.
     * 
     * @param key the property key
     * @param type the expected type of the value
     * @param <T> the type parameter
     * @return an Optional containing the value if present and of the correct type
     */
    default <T> Optional<T> getProperty(String key, Class<T> type) {
        return getContext().getProperty(key, type);
    }
    
    /**
     * Sets a property value in the menu context.
     * 
     * @param key the property key
     * @param value the property value
     */
    default void setProperty(String key, Object value) {
        getContext().setProperty(key, value);
    }
    
    /**
     * Checks if this menu is a list menu (paginated).
     * 
     * @return true if this is a list menu, false otherwise
     */
    boolean isListMenu();
    
    /**
     * Checks if this menu is a custom menu (with viewport).
     * 
     * @return true if this is a custom menu, false otherwise
     */
    boolean isCustomMenu();
    
    /**
     * Gets the current page number if this is a list menu.
     * 
     * @return the current page (1-based), or 1 if not a list menu
     */
    default int getCurrentPage() {
        return getProperty("currentPage", Integer.class).orElse(1);
    }
    
    /**
     * Gets the total number of pages if this is a list menu.
     * 
     * @return the total pages, or 1 if not a list menu
     */
    default int getTotalPages() {
        return getProperty("totalPages", Integer.class).orElse(1);
    }
    
    /**
     * Navigates to a specific page if this is a list menu.
     * 
     * @param page the page number (1-based)
     * @return true if navigation was successful, false otherwise
     */
    boolean navigateToPage(int page);
    
    /**
     * Adds a close handler that will be called when the menu is closed.
     * 
     * @param handler the close handler
     */
    void onClose(Runnable handler);
    
    /**
     * Adds an update handler that will be called when the menu is updated.
     * 
     * @param handler the update handler
     */
    void onUpdate(Runnable handler);
}