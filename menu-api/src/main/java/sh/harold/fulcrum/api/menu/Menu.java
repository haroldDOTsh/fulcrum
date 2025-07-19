package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a menu that can be displayed to players.
 * This interface provides the core structure for all menu types in the system.
 * 
 * <p>Menus are immutable once created and thread-safe for concurrent access.
 * All menu modifications should be done through builders and result in new menu instances.
 */
public interface Menu {

    /**
     * Gets the unique identifier for this menu.
     * 
     * @return the menu ID
     */
    String getId();

    /**
     * Gets the display title of this menu.
     * 
     * @return the menu title as a Component
     */
    Component getTitle();

    /**
     * Gets the size of this menu in slots.
     * Must be a multiple of 9 and between 9 and 54.
     * 
     * @return the menu size
     */
    int getSize();

    /**
     * Gets the type of this menu.
     * 
     * @return the menu type
     */
    MenuType getType();

    /**
     * Gets all menu items in this menu.
     * 
     * @return a list of menu items
     */
    List<MenuItem> getItems();

    /**
     * Gets the menu item at the specified slot.
     * 
     * @param slot the slot index (0-based)
     * @return the menu item at that slot, or null if empty
     * @throws IllegalArgumentException if slot is out of bounds
     */
    MenuItem getItem(int slot);

    /**
     * Gets the owner of this menu (the player it was created for).
     * 
     * @return the owner's UUID
     */
    UUID getOwner();

    /**
     * Gets the creation time of this menu.
     * 
     * @return the creation time in milliseconds since epoch
     */
    long getCreationTime();

    /**
     * Checks if this menu allows items to be taken from it.
     * 
     * @return true if items can be taken, false otherwise
     */
    boolean isItemTakingAllowed();

    /**
     * Checks if this menu allows items to be placed into it.
     * 
     * @return true if items can be placed, false otherwise
     */
    boolean isItemPlacingAllowed();

    /**
     * Gets the menu properties for this menu.
     * 
     * @return the menu properties
     */
    MenuProperties getProperties();

    /**
     * Checks if this menu has a specific property.
     * 
     * @param property the property to check
     * @return true if the property exists, false otherwise
     */
    boolean hasProperty(String property);

    /**
     * Gets a property value from this menu.
     * 
     * @param property the property name
     * @param defaultValue the default value if the property doesn't exist
     * @param <T> the type of the property value
     * @return the property value or default value
     */
    <T> T getProperty(String property, T defaultValue);

    /**
     * Creates a copy of this menu with a new owner.
     * 
     * @param newOwner the new owner's UUID
     * @return a new menu instance with the same properties but different owner
     */
    Menu copyForOwner(UUID newOwner);

    /**
     * Validates that this menu is properly configured.
     * 
     * @throws IllegalStateException if the menu is invalid
     */
    void validate();

    /**
     * Refreshes the menu contents.
     * This method is called when the menu needs to update its dynamic content.
     * 
     * @return a CompletableFuture that completes when the refresh is done
     */
    CompletableFuture<Void> refresh();

    /**
     * Called when this menu is opened for a player.
     * 
     * @param playerId the UUID of the player
     * @return a CompletableFuture that completes when the open operation is done
     */
    CompletableFuture<Void> onOpen(UUID playerId);

    /**
     * Called when this menu is closed for a player.
     * 
     * @param playerId the UUID of the player
     * @return a CompletableFuture that completes when the close operation is done
     */
    CompletableFuture<Void> onClose(UUID playerId);

    /**
     * Called when an item in this menu is clicked.
     * 
     * @param playerId the UUID of the player who clicked
     * @param slot the slot that was clicked
     * @param clickType the type of click
     * @param clickedItem the item that was clicked
     * @return a CompletableFuture that completes when the click is handled
     */
    CompletableFuture<Void> onClick(UUID playerId, int slot, org.bukkit.event.inventory.ClickType clickType, ItemStack clickedItem);

    /**
     * Enumeration of menu types.
     */
    enum MenuType {
        LIST,
        CONFIRMATION,
        CUSTOM
    }

    /**
     * Interface for menu properties.
     */
    interface MenuProperties {
        /**
         * Gets a property value.
         * 
         * @param key the property key
         * @return the property value, or null if not found
         */
        Object getProperty(String key);

        /**
         * Sets a property value.
         * 
         * @param key the property key
         * @param value the property value
         */
        void setProperty(String key, Object value);

        /**
         * Checks if a property exists.
         * 
         * @param key the property key
         * @return true if the property exists, false otherwise
         */
        boolean hasProperty(String key);

        /**
         * Removes a property.
         * 
         * @param key the property key
         */
        void removeProperty(String key);

        /**
         * Gets all property keys.
         * 
         * @return a set of all property keys
         */
        java.util.Set<String> getPropertyKeys();
    }
}