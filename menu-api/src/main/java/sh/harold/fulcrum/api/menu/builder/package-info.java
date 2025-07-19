/**
 * Builder pattern implementations for menu construction.
 * 
 * <p>This package provides fluent builder interfaces for constructing
 * menu objects with a type-safe, immutable approach. All builders
 * follow the same pattern of returning new instances for each modification.
 * 
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link sh.harold.fulcrum.api.menu.builder.MenuItemBuilder} - Builder for creating menu items</li>
 * </ul>
 * 
 * <h2>Builder Pattern</h2>
 * <p>All builders in this package follow these principles:
 * <ul>
 *   <li><strong>Immutability</strong> - Each method returns a new builder instance</li>
 *   <li><strong>Fluent API</strong> - Method chaining for readable configuration</li>
 *   <li><strong>Type Safety</strong> - Compile-time validation of configurations</li>
 *   <li><strong>Validation</strong> - Built-in validation before building</li>
 * </ul>
 * 
 * @since 1.0.0
 * @author Fulcrum Framework
 * @version 1.0.0
 */
package sh.harold.fulcrum.api.menu.builder;