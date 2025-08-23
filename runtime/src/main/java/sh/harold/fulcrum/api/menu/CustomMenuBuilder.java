package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Builder for creating custom menus with viewport support and flexible dimensions.
 * Custom menus allow for larger virtual inventories with scrollable viewports.
 * 
 * Example usage:
 * <pre>{@code
 * menuService.createMenuBuilder()
 *     .title(Component.text("Custom Shop"))
 *     .viewPort(4)
 *     .rows(8)
 *     .columns(12)
 *     .anchor(AnchorPoint.BOTTOM)
 *     .owner(plugin)
 *     .addButton(MenuButton.CLOSE, 3, 7)
 *     .addItem(shopItem1, 0, 0)
 *     .addItem(shopItem2, 0, 1)
 *     .fillEmpty(Material.GRAY_STAINED_GLASS_PANE)
 *     .buildAsync(player);
 * }</pre>
 */
public interface CustomMenuBuilder {
    
    /**
     * Sets the title of the menu.
     * Supports Adventure Components with color codes.
     * 
     * @param title the menu title
     * @return this builder
     */
    CustomMenuBuilder title(Component title);
    
    /**
     * Sets the title of the menu using a string.
     * Supports MiniMessage formatting and legacy color codes.
     * 
     * @param title the menu title string
     * @return this builder
     */
    CustomMenuBuilder title(String title);
    
    /**
     * Sets the viewport size in rows.
     * This is the visible window size that players see.
     * Must be between 1 and 6 (standard inventory sizes).
     * 
     * @param viewPortRows the number of visible rows
     * @return this builder
     * @throws IllegalArgumentException if viewPortRows is not between 1 and 6
     */
    CustomMenuBuilder viewPort(int viewPortRows);
    
    /**
     * Sets the total virtual rows for the menu.
     * This can be larger than the viewport for scrollable menus.
     * 
     * @param rows the total number of virtual rows
     * @return this builder
     * @throws IllegalArgumentException if rows is less than viewport rows
     */
    CustomMenuBuilder rows(int rows);
    
    /**
     * Sets the anchor point for the viewport.
     * Determines where the viewport is positioned relative to content.
     * 
     * @param anchor the anchor point
     * @return this builder
     */
    CustomMenuBuilder anchor(AnchorPoint anchor);
    
    /**
     * Sets both vertical and horizontal anchor points.
     * 
     * @param vertical the vertical anchor (TOP, CENTRE, BOTTOM)
     * @param horizontal the horizontal anchor (LEFT, CENTRE, RIGHT)
     * @return this builder
     */
    CustomMenuBuilder anchor(AnchorPoint.Vertical vertical, AnchorPoint.Horizontal horizontal);
    
    /**
     * Sets the number of virtual columns for the menu.
     * Default is 9 (standard inventory width).
     * 
     * @param columns the number of columns
     * @return this builder
     * @throws IllegalArgumentException if columns is less than 9
     */
    CustomMenuBuilder columns(int columns);
    
    /**
     * Sets the owner plugin for this menu.
     * Used for cleanup when plugins are disabled.
     * 
     * @param plugin the owner plugin
     * @return this builder
     */
    CustomMenuBuilder owner(Plugin plugin);
    
    /**
     * Adds a button at specific virtual coordinates.
     * Coordinates are in the virtual space, not viewport space.
     * 
     * @param button the button to add
     * @param row the virtual row (0-based)
     * @param column the virtual column (0-based)
     * @return this builder
     */
    CustomMenuBuilder addButton(MenuButton button, int row, int column);
    
    /**
     * Adds a button at a specific virtual slot.
     * 
     * @param button the button to add
     * @param virtualSlot the slot in virtual space
     * @return this builder
     */
    CustomMenuBuilder addButton(MenuButton button, int virtualSlot);
    
    /**
     * Adds a button using its pre-assigned slot.
     * 
     * @param button the button with slot already set
     * @return this builder
     * @throws IllegalArgumentException if button has no slot
     */
    CustomMenuBuilder addButton(MenuButton button);
    
    /**
     * Adds a display item at specific virtual coordinates.
     * 
     * @param item the item to add
     * @param row the virtual row (0-based)
     * @param column the virtual column (0-based)
     * @return this builder
     */
    CustomMenuBuilder addItem(MenuItem item, int row, int column);
    
    /**
     * Adds a display item at a specific virtual slot.
     * 
     * @param item the item to add
     * @param virtualSlot the slot in virtual space
     * @return this builder
     */
    CustomMenuBuilder addItem(MenuItem item, int virtualSlot);
    
    /**
     * Adds an ItemStack as a display item at specific coordinates.
     * 
     * @param itemStack the ItemStack to add
     * @param row the virtual row (0-based)
     * @param column the virtual column (0-based)
     * @return this builder
     */
    CustomMenuBuilder addItem(ItemStack itemStack, int row, int column);
    
    /**
     * Fills all empty slots with the specified material.
     * 
     * @param material the material to use for empty slots
     * @return this builder
     */
    CustomMenuBuilder fillEmpty(Material material);
    
    /**
     * Fills all empty slots with the specified item.
     * 
     * @param item the item to use for empty slots
     * @return this builder
     */
    CustomMenuBuilder fillEmpty(MenuItem item);
    
    /**
     * Fills empty slots with a custom name and material.
     * 
     * @param material the material to use
     * @param displayName the display name for empty slot items
     * @return this builder
     */
    CustomMenuBuilder fillEmpty(Material material, String displayName);
    
    /**
     * Adds scroll buttons for navigating the viewport.
     * Places them at appropriate positions based on menu layout.
     * 
     * @return this builder
     */
    CustomMenuBuilder addScrollButtons();
    
    /**
     * Adds custom scroll buttons at specified viewport slots.
     * 
     * @param upSlot viewport slot for scroll up button
     * @param downSlot viewport slot for scroll down button
     * @param leftSlot viewport slot for scroll left button
     * @param rightSlot viewport slot for scroll right button
     * @return this builder
     */
    CustomMenuBuilder addScrollButtons(int upSlot, int downSlot, int leftSlot, int rightSlot);
    
    /**
     * Sets a handler for viewport scroll events.
     *
     * @param handler the scroll event handler
     * @return this builder
     */
    CustomMenuBuilder onScroll(ScrollHandler handler);
    
    /**
     * Enables automatic refresh of menu content.
     *
     * @param intervalSeconds refresh interval in seconds
     * @return this builder
     */
    CustomMenuBuilder autoRefresh(int intervalSeconds);
    
    /**
     * Sets a dynamic content provider for automatic updates.
     * Called on each refresh cycle.
     *
     * @param provider the content provider
     * @return this builder
     */
    CustomMenuBuilder dynamicContent(Supplier<MenuItem[]> provider);
    
    /**
     * Sets the initial viewport offset.
     * 
     * @param rowOffset initial row offset (0-based)
     * @param columnOffset initial column offset (0-based)
     * @return this builder
     */
    CustomMenuBuilder initialOffset(int rowOffset, int columnOffset);
    
    /**
     * Sets whether to close the menu when clicking outside.
     * Default is true.
     * 
     * @param closeOnOutsideClick true to close on outside click
     * @return this builder
     */
    CustomMenuBuilder closeOnOutsideClick(boolean closeOnOutsideClick);
    
    /**
     * Adds a border around the viewport using the specified material.
     * 
     * @param borderMaterial the material for border items
     * @return this builder
     */
    CustomMenuBuilder addBorder(Material borderMaterial);
    
    /**
     * Adds a border around the viewport using the specified item.
     * 
     * @param borderItem the item to use for the border
     * @return this builder
     */
    CustomMenuBuilder addBorder(MenuItem borderItem);
    
    /**
     * Builds the menu asynchronously and opens it for the player.
     * 
     * @param player the player to open the menu for
     * @return a CompletableFuture that completes with the menu
     */
    CompletableFuture<Menu> buildAsync(Player player);
    
    /**
     * Builds the menu asynchronously without opening it.
     *
     * @return a CompletableFuture that completes with the menu
     */
    CompletableFuture<Menu> buildAsync();
    
    /**
     * Sets the parent menu for this menu, enabling back button navigation.
     * When a parent menu is specified, a back button will be automatically added
     * that navigates back to the specified parent menu.
     *
     * @param menuId the ID of the parent menu to navigate back to
     * @return this builder
     */
    CustomMenuBuilder parentMenu(String menuId);
    
    /**
     * Functional interface for scroll events.
     */
    @FunctionalInterface
    interface ScrollHandler {
        /**
         * Called when the viewport scrolls.
         * 
         * @param player the player who scrolled
         * @param oldRowOffset previous row offset
         * @param oldColumnOffset previous column offset
         * @param newRowOffset new row offset
         * @param newColumnOffset new column offset
         */
        void onScroll(Player player, int oldRowOffset, int oldColumnOffset, 
                     int newRowOffset, int newColumnOffset);
    }
}