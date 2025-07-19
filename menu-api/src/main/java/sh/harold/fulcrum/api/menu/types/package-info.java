/**
 * Menu type implementations for specialized menu behaviors.
 * 
 * <p>This package contains specific menu type implementations that extend
 * the base {@link sh.harold.fulcrum.api.menu.Menu} interface to provide
 * specialized functionality for common menu patterns.
 * 
 * <h2>Available Menu Types</h2>
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.types.ListMenu} - Paginated list-based menus with navigation</li>
 *   <li>{@link sh.harold.fulcrum.api.menu.types.ConfirmationMenu} - Simple confirmation dialogs</li>
 * </ul>
 * 
 * <h2>ListMenu</h2>
 * <p>The {@link sh.harold.fulcrum.api.menu.types.ListMenu} is designed for displaying
 * lists of items that may exceed the capacity of a single inventory screen. It provides:
 * <ul>
 *   <li>Automatic pagination</li>
 *   <li>Navigation controls (next/previous page)</li>
 *   <li>Configurable items per page</li>
 *   <li>Dynamic content loading</li>
 * </ul>
 * 
 * <h2>ConfirmationMenu</h2>
 * <p>The {@link sh.harold.fulcrum.api.menu.types.ConfirmationMenu} provides a standardized
 * way to create confirmation dialogs. It includes:
 * <ul>
 *   <li>Confirm and cancel buttons</li>
 *   <li>Configurable timeout</li>
 *   <li>Custom actions for each response</li>
 *   <li>Descriptive message display</li>
 * </ul>
 * 
 * @since 1.0.0
 * @author Fulcrum Framework
 * @version 1.0.0
 */
package sh.harold.fulcrum.api.menu.types;