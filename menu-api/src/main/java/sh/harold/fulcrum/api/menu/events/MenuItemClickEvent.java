package sh.harold.fulcrum.api.menu.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.MenuItem;

/**
 * Event fired when a menu item is clicked by a player.
 * This event is cancellable and can be used to prevent menu item clicks
 * or to perform additional actions when a menu item is clicked.
 */
public class MenuItemClickEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    
    private final Menu menu;
    private final MenuItem menuItem;
    private final int slot;
    private final ClickType clickType;
    private final ItemStack clickedItem;
    private final ItemStack cursorItem;
    private boolean cancelled = false;

    /**
     * Creates a new MenuItemClickEvent.
     *
     * @param player the player who clicked the item
     * @param menu the menu containing the item
     * @param menuItem the menu item that was clicked
     * @param slot the slot that was clicked
     * @param clickType the type of click performed
     * @param clickedItem the item that was clicked
     * @param cursorItem the item on the player's cursor
     */
    public MenuItemClickEvent(Player player, Menu menu, MenuItem menuItem, int slot, 
                             ClickType clickType, ItemStack clickedItem, ItemStack cursorItem) {
        super(player);
        this.menu = menu;
        this.menuItem = menuItem;
        this.slot = slot;
        this.clickType = clickType;
        this.clickedItem = clickedItem;
        this.cursorItem = cursorItem;
    }

    /**
     * Gets the menu containing the clicked item.
     *
     * @return the menu
     */
    public Menu getMenu() {
        return menu;
    }

    /**
     * Gets the menu item that was clicked.
     *
     * @return the menu item
     */
    public MenuItem getMenuItem() {
        return menuItem;
    }

    /**
     * Gets the slot that was clicked.
     *
     * @return the slot index
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Gets the type of click performed.
     *
     * @return the click type
     */
    public ClickType getClickType() {
        return clickType;
    }

    /**
     * Gets the item that was clicked.
     *
     * @return the clicked item
     */
    public ItemStack getClickedItem() {
        return clickedItem;
    }

    /**
     * Gets the item on the player's cursor.
     *
     * @return the cursor item
     */
    public ItemStack getCursorItem() {
        return cursorItem;
    }

    /**
     * Gets the ID of the menu containing the clicked item.
     *
     * @return the menu ID
     */
    public String getMenuId() {
        return menu.getId();
    }

    /**
     * Gets the type of the menu containing the clicked item.
     *
     * @return the menu type
     */
    public Menu.MenuType getMenuType() {
        return menu.getType();
    }

    /**
     * Checks if the click was a left click.
     *
     * @return true if left click, false otherwise
     */
    public boolean isLeftClick() {
        return clickType == ClickType.LEFT;
    }

    /**
     * Checks if the click was a right click.
     *
     * @return true if right click, false otherwise
     */
    public boolean isRightClick() {
        return clickType == ClickType.RIGHT;
    }

    /**
     * Checks if the click was a shift click.
     *
     * @return true if shift click, false otherwise
     */
    public boolean isShiftClick() {
        return clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT;
    }

    /**
     * Checks if the click was a middle click.
     *
     * @return true if middle click, false otherwise
     */
    public boolean isMiddleClick() {
        return clickType == ClickType.MIDDLE;
    }

    /**
     * Checks if the click was a double click.
     *
     * @return true if double click, false otherwise
     */
    public boolean isDoubleClick() {
        return clickType == ClickType.DOUBLE_CLICK;
    }

    /**
     * Checks if the click was a drop action.
     *
     * @return true if drop action, false otherwise
     */
    public boolean isDropAction() {
        return clickType == ClickType.DROP || clickType == ClickType.CONTROL_DROP;
    }

    /**
     * Checks if the click was a number key press.
     *
     * @return true if number key press, false otherwise
     */
    public boolean isNumberKey() {
        return clickType == ClickType.NUMBER_KEY;
    }

    /**
     * Checks if the menu item that was clicked is clickable.
     *
     * @return true if clickable, false otherwise
     */
    public boolean isMenuItemClickable() {
        return menuItem != null && menuItem.isClickable();
    }

    /**
     * Checks if the menu item that was clicked is visible.
     *
     * @return true if visible, false otherwise
     */
    public boolean isMenuItemVisible() {
        return menuItem != null && menuItem.isVisible();
    }

    /**
     * Checks if the menu item has the specified property.
     *
     * @param property the property to check
     * @return true if the menu item has the property, false otherwise
     */
    public boolean hasMenuItemProperty(String property) {
        return menuItem != null && menuItem.hasProperty(property);
    }

    /**
     * Gets a property value from the menu item.
     *
     * @param property the property name
     * @param defaultValue the default value if the property doesn't exist
     * @param <T> the type of the property value
     * @return the property value or default value
     */
    public <T> T getMenuItemProperty(String property, T defaultValue) {
        return menuItem != null ? menuItem.getProperty(property, defaultValue) : defaultValue;
    }

    /**
     * Checks if the menu has the specified property.
     *
     * @param property the property to check
     * @return true if the menu has the property, false otherwise
     */
    public boolean hasMenuProperty(String property) {
        return menu.hasProperty(property);
    }

    /**
     * Gets a property value from the menu.
     *
     * @param property the property name
     * @param defaultValue the default value if the property doesn't exist
     * @param <T> the type of the property value
     * @return the property value or default value
     */
    public <T> T getMenuProperty(String property, T defaultValue) {
        return menu.getProperty(property, defaultValue);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    @Override
    public String toString() {
        return "MenuItemClickEvent{" +
                "player=" + getPlayer().getName() +
                ", menu=" + menu.getId() +
                ", menuItem=" + (menuItem != null ? menuItem.getSlot() : "null") +
                ", slot=" + slot +
                ", clickType=" + clickType +
                ", cancelled=" + cancelled +
                '}';
    }
}