package sh.harold.fulcrum.api.menu.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import sh.harold.fulcrum.api.menu.Menu;

/**
 * Event fired when a menu is opened for a player.
 * This event is cancellable and can be used to prevent menu opening
 * or to perform actions when a menu is opened.
 */
public class MenuOpenEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    
    private final Menu menu;
    private boolean cancelled = false;

    /**
     * Creates a new MenuOpenEvent.
     *
     * @param player the player opening the menu
     * @param menu the menu being opened
     */
    public MenuOpenEvent(Player player, Menu menu) {
        super(player);
        this.menu = menu;
    }

    /**
     * Gets the menu being opened.
     *
     * @return the menu being opened
     */
    public Menu getMenu() {
        return menu;
    }

    /**
     * Gets the ID of the menu being opened.
     *
     * @return the menu ID
     */
    public String getMenuId() {
        return menu.getId();
    }

    /**
     * Gets the type of the menu being opened.
     *
     * @return the menu type
     */
    public Menu.MenuType getMenuType() {
        return menu.getType();
    }

    /**
     * Gets the size of the menu being opened.
     *
     * @return the menu size
     */
    public int getMenuSize() {
        return menu.getSize();
    }

    /**
     * Checks if the menu being opened has the specified property.
     *
     * @param property the property to check
     * @return true if the menu has the property, false otherwise
     */
    public boolean hasMenuProperty(String property) {
        return menu.hasProperty(property);
    }

    /**
     * Gets a property value from the menu being opened.
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
        return "MenuOpenEvent{" +
                "player=" + getPlayer().getName() +
                ", menu=" + menu.getId() +
                ", menuType=" + menu.getType() +
                ", cancelled=" + cancelled +
                '}';
    }
}