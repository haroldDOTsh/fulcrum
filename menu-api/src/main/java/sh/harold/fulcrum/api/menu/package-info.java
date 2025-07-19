/**
 * Core menu API for the Fulcrum framework.
 * 
 * <p>This package provides a comprehensive menu system for creating interactive
 * inventory-based user interfaces in Minecraft plugins. The API follows modern
 * design patterns and provides a fluent, type-safe way to create and manage menus.
 * 
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.MenuService} - Main service interface for menu operations</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.Menu} - Core menu representation</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.MenuItem} - Individual menu item representation</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.MenuBuilder} - Fluent builder for menu creation</li>
 * </ul>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Type Safety</strong> - Compile-time validation of menu configurations</li>
 *   <li><strong>Async Operations</strong> - All potentially blocking operations return CompletableFuture</li>
 *   <li><strong>Security</strong> - Built-in rate limiting, input validation, and session management</li>
 *   <li><strong>Immutable Design</strong> - Menu definitions are immutable once created</li>
 *   <li><strong>Event Integration</strong> - Full integration with Bukkit's event system</li>
 *   <li><strong>Builder Pattern</strong> - Fluent API for easy menu construction</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Get the menu service
 * MenuService menuService = // ... obtain service instance
 * 
 * // Create a list menu
 * ListMenu menu = menuService.createListMenu(player, "example-menu")
 *     .title("Example Menu")
 *     .size(27)
 *     .addItem(new ItemStack(Material.DIAMOND), context -> {
 *         player.sendMessage("You clicked a diamond!");
 *         context.closeMenu();
 *     })
 *     .build();
 * 
 * // Open the menu
 * menuService.openMenu(player, menu);
 * }</pre>
 * 
 * <h2>Menu Types</h2>
 * <p>The API supports several predefined menu types in the {@link sh.harold.fulcrum.api.menu.types} package:
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.types.ListMenu} - Paginated list-based menus</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.types.ConfirmationMenu} - Simple confirmation dialogs</li>
 * </ul>
 * 
 * <h2>Security</h2>
 * <p>The menu system includes comprehensive security features in the 
 * {@link sh.harold.fulcrum.api.menu.security} package:
 * <ul>
 *   <li>Rate limiting to prevent spam</li>
 *   <li>Input validation for all menu operations</li>
 *   <li>Session management and timeout handling</li>
 *   <li>Permission-based access control</li>
 * </ul>
 * 
 * <h2>Events</h2>
 * <p>The API fires several events that can be listened to in the 
 * {@link sh.harold.fulcrum.api.menu.events} package:
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuOpenEvent} - Fired when a menu is opened</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuCloseEvent} - Fired when a menu is closed</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.events.MenuItemClickEvent} - Fired when a menu item is clicked</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>All interfaces in this API are designed to be thread-safe. Menu definitions
 * are immutable once created, and all mutable operations are properly synchronized.
 * The API uses concurrent collections and atomic operations where appropriate.
 * 
 * <h2>Performance</h2>
 * <p>The menu system is designed for high performance with:
 * <ul>
 *   <li>Object pooling for frequently created objects</li>
 *   <li>Lazy loading of menu content</li>
 *   <li>Efficient event handling</li>
 *   <li>Minimal memory footprint</li>
 * </ul>
 * 
 * @since 1.0.0
 * @author Fulcrum Framework
 * @version 1.0.0
 */
package sh.harold.fulcrum.api.menu;