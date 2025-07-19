package sh.harold.fulcrum.api.menu.types;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuItem;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Represents a list-based menu with pagination support.
 * This menu type is designed for displaying lists of items that may exceed
 * the size of a single inventory screen.
 * 
 * <p>List menus automatically handle pagination controls and provide
 * methods for navigating between pages.
 */
public interface ListMenu extends Menu {

    /**
     * Gets the current page number (0-based).
     * 
     * @return the current page number
     */
    int getCurrentPage();

    /**
     * Gets the total number of pages.
     * 
     * @return the total number of pages
     */
    int getTotalPages();

    /**
     * Gets the maximum number of items per page.
     * 
     * @return the items per page
     */
    int getItemsPerPage();

    /**
     * Gets the list items for the current page.
     * 
     * @return the list items for the current page
     */
    List<MenuItem> getListItems();

    /**
     * Gets all list items across all pages.
     * 
     * @return all list items
     */
    List<MenuItem> getAllListItems();

    /**
     * Checks if there is a next page.
     * 
     * @return true if there is a next page, false otherwise
     */
    boolean hasNextPage();

    /**
     * Checks if there is a previous page.
     * 
     * @return true if there is a previous page, false otherwise
     */
    boolean hasPreviousPage();

    /**
     * Creates a new list menu with the next page displayed.
     * 
     * @return a new list menu instance showing the next page
     * @throws IllegalStateException if there is no next page
     */
    ListMenu nextPage();

    /**
     * Creates a new list menu with the previous page displayed.
     * 
     * @return a new list menu instance showing the previous page
     * @throws IllegalStateException if there is no previous page
     */
    ListMenu previousPage();

    /**
     * Creates a new list menu with the specified page displayed.
     * 
     * @param page the page number (0-based)
     * @return a new list menu instance showing the specified page
     * @throws IllegalArgumentException if page is invalid
     */
    ListMenu goToPage(int page);

    /**
     * Gets the slot where the next page button is located.
     * 
     * @return the next page button slot, or -1 if not set
     */
    int getNextPageSlot();

    /**
     * Gets the slot where the previous page button is located.
     * 
     * @return the previous page button slot, or -1 if not set
     */
    int getPreviousPageSlot();

    /**
     * Gets the slot where the page info is displayed.
     * 
     * @return the page info slot, or -1 if not set
     */
    int getPageInfoSlot();

    /**
     * Gets the ItemStack used for the next page button.
     * 
     * @return the next page button ItemStack
     */
    ItemStack getNextPageButton();

    /**
     * Gets the ItemStack used for the previous page button.
     * 
     * @return the previous page button ItemStack
     */
    ItemStack getPreviousPageButton();

    /**
     * Gets the ItemStack used for displaying page information.
     * 
     * @return the page info ItemStack
     */
    ItemStack getPageInfoButton();

    /**
     * Gets the slots used for displaying list items.
     * 
     * @return an array of slots used for list items
     */
    int[] getListItemSlots();

    /**
     * Builder interface for creating ListMenu instances.
     */
    interface Builder {
        /**
         * Sets the title of the list menu.
         * 
         * @param title the menu title
         * @return this builder instance
         */
        Builder title(Component title);

        /**
         * Sets the title of the list menu using a string.
         * 
         * @param title the menu title as a string
         * @return this builder instance
         */
        Builder title(String title);

        /**
         * Sets the size of the list menu.
         * 
         * @param size the menu size
         * @return this builder instance
         */
        Builder size(int size);

        /**
         * Sets the size of the list menu using rows.
         * 
         * @param rows the number of rows
         * @return this builder instance
         */
        Builder rows(int rows);

        /**
         * Sets the owner of the list menu.
         * 
         * @param owner the owner's UUID
         * @return this builder instance
         */
        Builder owner(UUID owner);

        /**
         * Sets the player for the list menu.
         * 
         * @param player the player
         * @return this builder instance
         */
        Builder player(Player player);

        /**
         * Sets the items per page for the list menu.
         * 
         * @param itemsPerPage the items per page
         * @return this builder instance
         */
        Builder itemsPerPage(int itemsPerPage);

        /**
         * Sets the slots where list items should be displayed.
         * 
         * @param slots the slots for list items
         * @return this builder instance
         */
        Builder listItemSlots(int... slots);

        /**
         * Adds a list item to the menu.
         * 
         * @param item the menu item to add
         * @return this builder instance
         */
        Builder addItem(MenuItem item);

        /**
         * Adds multiple list items to the menu.
         * 
         * @param items the menu items to add
         * @return this builder instance
         */
        Builder addItems(List<MenuItem> items);

        /**
         * Adds a list item using an ItemStack and click handler.
         * 
         * @param itemStack the ItemStack for the item
         * @param clickHandler the click handler
         * @return this builder instance
         */
        Builder addItem(ItemStack itemStack, MenuItem.ClickHandler clickHandler);

        /**
         * Adds list items dynamically using a provider function.
         * 
         * @param itemProvider the function to provide items
         * @return this builder instance
         */
        Builder addItems(Function<Player, List<MenuItem>> itemProvider);

        /**
         * Sets the next page button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @return this builder instance
         */
        Builder nextPageButton(int slot, ItemStack itemStack);

        /**
         * Sets the previous page button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @return this builder instance
         */
        Builder previousPageButton(int slot, ItemStack itemStack);

        /**
         * Sets the page info button.
         * 
         * @param slot the slot for the button
         * @param itemStack the ItemStack for the button
         * @return this builder instance
         */
        Builder pageInfoButton(int slot, ItemStack itemStack);

        /**
         * Sets the initial page to display.
         * 
         * @param page the initial page (0-based)
         * @return this builder instance
         */
        Builder initialPage(int page);

        /**
         * Sets a property on the menu.
         * 
         * @param key the property key
         * @param value the property value
         * @return this builder instance
         */
        Builder property(String key, Object value);

        /**
         * Builds the list menu instance.
         * 
         * @return the constructed list menu
         */
        ListMenu build();

        /**
         * Builds the list menu instance asynchronously.
         * 
         * @return a CompletableFuture containing the constructed list menu
         */
        CompletableFuture<ListMenu> buildAsync();
    }
}