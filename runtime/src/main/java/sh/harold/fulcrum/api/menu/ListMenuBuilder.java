package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Builder for creating paginated list menus.
 * List menus automatically handle pagination and provide navigation controls.
 * <p>
 * Example usage:
 * <pre>{@code
 * menuService.createListMenu()
 *     .title(Component.text("Player List"))
 *     .rows(6)
 *     .addBorder(Material.BLACK_STAINED_GLASS_PANE)
 *     .addButton(MenuButton.CLOSE.slot(49))
 *     .addItems(players, player -> MenuButton.builder(Material.PLAYER_HEAD)
 *         .name("<yellow>" + player.getName())
 *         .onClick(p -> p.sendMessage("You clicked " + player.getName()))
 *         .build())
 *     .initialPage(1)
 *     .buildAsync(player);
 * }</pre>
 */
public interface ListMenuBuilder {

    /**
     * Sets the title of the menu.
     * Supports Adventure Components with color codes.
     *
     * @param title the menu title
     * @return this builder
     */
    ListMenuBuilder title(Component title);

    /**
     * Sets the title of the menu using a string.
     * Supports MiniMessage formatting and legacy color codes.
     *
     * @param title the menu title string
     * @return this builder
     */
    ListMenuBuilder title(String title);

    /**
     * Sets the number of rows for the menu.
     * Must be between 1 and 6 (inclusive).
     * Default is 6 rows.
     *
     * @param rows the number of rows
     * @return this builder
     * @throws IllegalArgumentException if rows is not between 1 and 6
     */
    ListMenuBuilder rows(int rows);

    /**
     * Adds a border around the menu using the specified material.
     * The border occupies the outer edge slots of the inventory.
     *
     * @param borderMaterial the material to use for border items
     * @return this builder
     */
    ListMenuBuilder addBorder(Material borderMaterial);

    /**
     * Adds a border around the menu using the specified item.
     * The border occupies the outer edge slots of the inventory.
     *
     * @param borderItem the item to use for the border
     * @return this builder
     */
    ListMenuBuilder addBorder(MenuItem borderItem);

    /**
     * Adds a border with custom name and material.
     *
     * @param borderMaterial the material to use
     * @param borderName     the display name for border items
     * @return this builder
     */
    ListMenuBuilder addBorder(Material borderMaterial, String borderName);

    /**
     * Adds a button to the menu at a specific slot.
     * Buttons are preserved across pages.
     *
     * @param button the button to add
     * @param slot   the slot position
     * @return this builder
     */
    ListMenuBuilder addButton(MenuButton button, int slot);

    /**
     * Adds a button to the menu.
     * The button must have a slot assigned.
     *
     * @param button the button to add (must have slot)
     * @return this builder
     * @throws IllegalArgumentException if button has no slot
     */
    ListMenuBuilder addButton(MenuButton button);

    /**
     * Adds multiple buttons to the menu.
     * Each button must have a slot assigned.
     *
     * @param buttons the buttons to add
     * @return this builder
     */
    ListMenuBuilder addButtons(MenuButton... buttons);

    /**
     * Adds a collection of items to display in the list.
     * These items will be paginated automatically.
     *
     * @param items the items to display
     * @return this builder
     */
    ListMenuBuilder addItems(Collection<? extends MenuItem> items);

    /**
     * Adds items by transforming a collection of objects.
     * The transformer function converts each object to a MenuItem.
     *
     * @param items       the source items
     * @param transformer function to convert items to MenuItems
     * @param <T>         the type of source items
     * @return this builder
     */
    <T> ListMenuBuilder addItems(Collection<T> items, Function<T, MenuItem> transformer);

    /**
     * Adds items from an array.
     *
     * @param items the items to add
     * @return this builder
     */
    ListMenuBuilder addItems(MenuItem... items);

    /**
     * Adds items from ItemStacks.
     * Each ItemStack is wrapped as a display item.
     *
     * @param items the ItemStacks to add
     * @return this builder
     */
    ListMenuBuilder addItemStacks(Collection<ItemStack> items);

    /**
     * Sets the initial page to display.
     * Pages are 1-based (first page is 1).
     *
     * @param page the initial page number
     * @return this builder
     */
    ListMenuBuilder initialPage(int page);

    /**
     * Sets the slot range for content items.
     * By default, uses all available slots not occupied by buttons/border.
     *
     * @param startSlot the first slot for content (inclusive)
     * @param endSlot   the last slot for content (inclusive)
     * @return this builder
     */
    ListMenuBuilder contentSlots(int startSlot, int endSlot);


    /**
     * Sets a custom empty message when there are no items.
     *
     * @param emptyMessage the message to display
     * @return this builder
     */
    ListMenuBuilder emptyMessage(Component emptyMessage);

    /**
     * Sets a handler to be called when the page changes.
     *
     * @param handler the page change handler (receives new page number)
     * @return this builder
     */
    ListMenuBuilder onPageChange(PageChangeHandler handler);

    /**
     * Enables automatic refresh of the menu content.
     *
     * @param intervalSeconds refresh interval in seconds
     * @return this builder
     */
    ListMenuBuilder autoRefresh(int intervalSeconds);

    /**
     * Sets whether to close the menu when clicking outside.
     * Default is true.
     *
     * @param closeOnOutsideClick true to close on outside click
     * @return this builder
     */
    ListMenuBuilder closeOnOutsideClick(boolean closeOnOutsideClick);

    /**
     * Fills all empty slots with the specified material.
     * Empty slots are those not occupied by border, buttons, or content items.
     *
     * @param material the material to use for empty slots
     * @return this builder
     */
    ListMenuBuilder fillEmpty(Material material);

    /**
     * Fills all empty slots with the specified item.
     * Empty slots are those not occupied by border, buttons, or content items.
     *
     * @param item the item to use for empty slots
     * @return this builder
     */
    ListMenuBuilder fillEmpty(MenuItem item);

    /**
     * Fills empty slots with a custom name and material.
     * Empty slots are those not occupied by border, buttons, or content items.
     *
     * @param material    the material to use
     * @param displayName the display name for empty slot items
     * @return this builder
     */
    ListMenuBuilder fillEmpty(Material material, String displayName);

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
    ListMenuBuilder parentMenu(String menuId);


    /**
     * Functional interface for page change events.
     */
    @FunctionalInterface
    interface PageChangeHandler {
        /**
         * Called when the page changes.
         *
         * @param player  the player viewing the menu
         * @param oldPage the previous page number
         * @param newPage the new page number
         */
        void onPageChange(Player player, int oldPage, int newPage);
    }
}