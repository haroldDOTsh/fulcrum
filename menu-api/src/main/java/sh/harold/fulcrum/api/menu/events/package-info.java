/**
 * Event classes for menu system integration with Bukkit's event system.
 * 
 * <p>This package contains all event classes that are fired during menu operations.
 * These events allow plugins to hook into the menu system and perform custom
 * actions when menus are opened, closed, or interacted with.
 * 
 * <h2>Available Events</h2>
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuOpenEvent} - Fired when a menu is opened</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuCloseEvent} - Fired when a menu is closed</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuItemClickEvent} - Fired when a menu item is clicked</li>
 * </ul>
 * 
 * <h2>Event Handling</h2>
 * <p>All events in this package are standard Bukkit events and can be handled
 * using the {@code @EventHandler} annotation in your plugin's event listeners.
 * 
 * <h2>Cancellation</h2>
 * <p>Most events implement {@link org.bukkit.event.Cancellable} and can be
 * cancelled to prevent the default behavior from occurring.
 * 
 * @since 1.0.0
 * @author Fulcrum Framework
 * @version 1.0.0
 */
package sh.harold.fulcrum.api.menu.events;