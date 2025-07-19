package sh.harold.fulcrum.api.menu.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import sh.harold.fulcrum.api.menu.Menu;

/**
 * Event fired when a menu is closed for a player.
 * This event is cancellable and can be used to prevent menu closing
 * or to perform cleanup actions when a menu is closed.
 */
public class MenuCloseEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    
    private final Menu menu;
    private final CloseReason reason;
    private boolean cancelled = false;

    /**
     * Creates a new MenuCloseEvent.
     *
     * @param player the player closing the menu
     * @param menu the menu being closed
     * @param reason the reason for closing the menu
     */
    public MenuCloseEvent(Player player, Menu menu, CloseReason reason) {
        super(player);
        this.menu = menu;
        this.reason = reason;
    }

    /**
     * Gets the menu being closed.
     *
     * @return the menu being closed
     */
    public Menu getMenu() {
        return menu;
    }

    /**
     * Gets the reason for closing the menu.
     *
     * @return the close reason
     */
    public CloseReason getReason() {
        return reason;
    }

    /**
     * Gets the ID of the menu being closed.
     *
     * @return the menu ID
     */
    public String getMenuId() {
        return menu.getId();
    }

    /**
     * Gets the type of the menu being closed.
     *
     * @return the menu type
     */
    public Menu.MenuType getMenuType() {
        return menu.getType();
    }

    /**
     * Gets the size of the menu being closed.
     *
     * @return the menu size
     */
    public int getMenuSize() {
        return menu.getSize();
    }

    /**
     * Checks if the menu was closed by the player.
     *
     * @return true if closed by player, false otherwise
     */
    public boolean isClosedByPlayer() {
        return reason == CloseReason.PLAYER;
    }

    /**
     * Checks if the menu was closed by the plugin.
     *
     * @return true if closed by plugin, false otherwise
     */
    public boolean isClosedByPlugin() {
        return reason == CloseReason.PLUGIN;
    }

    /**
     * Checks if the menu was closed due to an error.
     *
     * @return true if closed due to error, false otherwise
     */
    public boolean isClosedByError() {
        return reason == CloseReason.ERROR;
    }

    /**
     * Checks if the menu was closed due to a timeout.
     *
     * @return true if closed due to timeout, false otherwise
     */
    public boolean isClosedByTimeout() {
        return reason == CloseReason.TIMEOUT;
    }

    /**
     * Checks if the menu being closed has the specified property.
     *
     * @param property the property to check
     * @return true if the menu has the property, false otherwise
     */
    public boolean hasMenuProperty(String property) {
        return menu.hasProperty(property);
    }

    /**
     * Gets a property value from the menu being closed.
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
        return "MenuCloseEvent{" +
                "player=" + getPlayer().getName() +
                ", menu=" + menu.getId() +
                ", menuType=" + menu.getType() +
                ", reason=" + reason +
                ", cancelled=" + cancelled +
                '}';
    }

    /**
     * Enumeration of possible reasons for menu closure.
     */
    public enum CloseReason {
        /**
         * The player closed the menu (e.g., by pressing escape or clicking outside).
         */
        PLAYER,
        
        /**
         * The plugin closed the menu programmatically.
         */
        PLUGIN,
        
        /**
         * The menu was closed due to an error.
         */
        ERROR,
        
        /**
         * The menu was closed due to a timeout.
         */
        TIMEOUT,
        
        /**
         * The menu was closed because the player disconnected.
         */
        DISCONNECT,
        
        /**
         * The menu was closed because the server is shutting down.
         */
        SHUTDOWN,
        
        /**
         * The menu was closed for an unknown reason.
         */
        UNKNOWN
    }
}