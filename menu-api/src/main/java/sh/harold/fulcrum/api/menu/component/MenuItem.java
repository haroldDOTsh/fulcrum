package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Base interface for all menu items (buttons and display items).
 * Provides common functionality for items that can be placed in a menu.
 */
public interface MenuItem {
    
    /**
     * Gets the display name of this menu item.
     * 
     * @return the item's display name as an Adventure Component
     */
    Component getName();
    
    /**
     * Gets the lore (description) of this menu item.
     * 
     * @return list of lore lines as Adventure Components
     */
    List<Component> getLore();
    
    /**
     * Gets the ItemStack representation of this menu item.
     * This is what will be displayed in the inventory GUI.
     * 
     * @return the ItemStack to display
     */
    ItemStack getDisplayItem();
    
    /**
     * Sets or updates the display item after creation.
     * Useful for dynamic item updates.
     * 
     * @param itemStack the new ItemStack to display
     */
    void setDisplayItem(ItemStack itemStack);
    
    /**
     * Gets the slot position this item should be placed at.
     * Returns -1 if no specific slot is set.
     * 
     * @return the slot position or -1 if unset
     */
    int getSlot();
    
    /**
     * Checks if this menu item has a specific slot assigned.
     * 
     * @return true if a slot is assigned, false otherwise
     */
    boolean hasSlot();
}