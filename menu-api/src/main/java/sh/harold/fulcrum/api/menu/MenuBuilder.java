package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.query.streaming.PaginationSupport;
import sh.harold.fulcrum.api.data.query.streaming.AsyncResultStream;
import sh.harold.fulcrum.api.data.query.streaming.StreamingExecutor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Builder interface for creating menus with a fluent API.
 * This interface provides a type-safe way to construct menu instances
 * with proper validation and immutable results.
 * 
 * <p>All builders are immutable - each method returns a new builder instance
 * with the specified modification applied.
 */
public interface MenuBuilder {

    /**
     * Sets the title of the menu.
     * 
     * @param title the menu title
     * @return a new builder instance with the title set
     * @throws IllegalArgumentException if title is null
     */
    MenuBuilder title(Component title);

    /**
     * Sets the title of the menu using a string.
     * 
     * @param title the menu title as a string
     * @return a new builder instance with the title set
     * @throws IllegalArgumentException if title is null
     */
    MenuBuilder title(String title);

    /**
     * Sets the size of the menu.
     * Size must be a multiple of 9 and between 9 and 54.
     * 
     * @param size the menu size
     * @return a new builder instance with the size set
     * @throws IllegalArgumentException if size is invalid
     */
    MenuBuilder size(int size);

    /**
     * Sets the size of the menu using rows.
     * 
     * @param rows the number of rows (1-6)
     * @return a new builder instance with the size set
     * @throws IllegalArgumentException if rows is invalid
     */
    MenuBuilder rows(int rows);

    /**
     * Sets the owner of the menu.
     * 
     * @param owner the owner's UUID
     * @return a new builder instance with the owner set
     * @throws IllegalArgumentException if owner is null
     */
    MenuBuilder owner(UUID owner);

    /**
     * Sets whether items can be taken from the menu.
     * 
     * @param allowed true to allow item taking, false to disallow
     * @return a new builder instance with the setting applied
     */
    MenuBuilder allowItemTaking(boolean allowed);

    /**
     * Sets whether items can be placed into the menu.
     * 
     * @param allowed true to allow item placing, false to disallow
     * @return a new builder instance with the setting applied
     */
    MenuBuilder allowItemPlacing(boolean allowed);

    /**
     * Adds a menu item to the menu.
     * 
     * @param item the menu item to add
     * @return a new builder instance with the item added
     * @throws IllegalArgumentException if item is null
     */
    MenuBuilder item(MenuItem item);

    /**
     * Adds a menu item at a specific slot.
     * 
     * @param slot the slot position (0-based)
     * @param item the menu item to add
     * @return a new builder instance with the item added
     * @throws IllegalArgumentException if slot is invalid or item is null
     */
    MenuBuilder item(int slot, MenuItem item);

    /**
     * Adds a simple menu item with an ItemStack and click handler.
     * 
     * @param slot the slot position (0-based)
     * @param itemStack the ItemStack for the item
     * @param clickHandler the click handler
     * @return a new builder instance with the item added
     * @throws IllegalArgumentException if any parameter is null or slot is invalid
     */
    MenuBuilder item(int slot, ItemStack itemStack, MenuItem.ClickHandler clickHandler);

    /**
     * Adds a simple menu item with an ItemStack, display name, and click handler.
     * 
     * @param slot the slot position (0-based)
     * @param itemStack the ItemStack for the item
     * @param displayName the display name
     * @param clickHandler the click handler
     * @return a new builder instance with the item added
     * @throws IllegalArgumentException if any parameter is null or slot is invalid
     */
    MenuBuilder item(int slot, ItemStack itemStack, Component displayName, MenuItem.ClickHandler clickHandler);

    /**
     * Removes a menu item from the specified slot.
     * 
     * @param slot the slot position (0-based)
     * @return a new builder instance with the item removed
     * @throws IllegalArgumentException if slot is invalid
     */
    MenuBuilder removeItem(int slot);

    /**
     * Fills empty slots with the specified item.
     * 
     * @param itemStack the ItemStack to use for filling
     * @return a new builder instance with empty slots filled
     * @throws IllegalArgumentException if itemStack is null
     */
    MenuBuilder fillEmpty(ItemStack itemStack);

    /**
     * Fills the borders of the menu with the specified item.
     * 
     * @param itemStack the ItemStack to use for borders
     * @return a new builder instance with borders filled
     * @throws IllegalArgumentException if itemStack is null
     */
    MenuBuilder fillBorders(ItemStack itemStack);

    /**
     * Sets a property on the menu.
     * 
     * @param key the property key
     * @param value the property value
     * @return a new builder instance with the property set
     * @throws IllegalArgumentException if key is null
     */
    MenuBuilder property(String key, Object value);

    /**
     * Removes a property from the menu.
     * 
     * @param key the property key
     * @return a new builder instance with the property removed
     * @throws IllegalArgumentException if key is null
     */
    MenuBuilder removeProperty(String key);

    /**
     * Sets the menu type.
     *
     * @param type the menu type
     * @return a new builder instance with the type set
     * @throws IllegalArgumentException if type is null
     */
    MenuBuilder type(Menu.MenuType type);

    // ===== UNIFIED ADDITEMS() METHODS =====

    /**
     * Adds multiple menu items from a static collection.
     *
     * @param items the menu items to add
     * @return a new builder instance with the items added
     * @throws IllegalArgumentException if items is null
     */
    MenuBuilder addItems(List<MenuItem> items);

    /**
     * Adds multiple menu items using varargs.
     *
     * @param items the menu items to add
     * @return a new builder instance with the items added
     * @throws IllegalArgumentException if items is null
     */
    MenuBuilder addItems(MenuItem... items);

    /**
     * Adds items from a data-api query builder.
     * Uses default conversion from CrossSchemaResult to MenuItem.
     *
     * @param query the query builder to execute
     * @return a new builder instance with query results added
     * @throws IllegalArgumentException if query is null
     */
    MenuBuilder addItems(CrossSchemaQueryBuilder query);

    /**
     * Adds items from a data-api query builder with custom conversion.
     *
     * @param query the query builder to execute
     * @param converter function to convert CrossSchemaResult to MenuItem
     * @return a new builder instance with converted query results added
     * @throws IllegalArgumentException if query or converter is null
     */
    MenuBuilder addItems(CrossSchemaQueryBuilder query, Function<CrossSchemaResult, MenuItem> converter);

    /**
     * Adds items from an async stream of CrossSchemaResults.
     * Uses default conversion from CrossSchemaResult to MenuItem.
     *
     * @param stream the async stream of results
     * @return a new builder instance with stream results added
     * @throws IllegalArgumentException if stream is null
     */
    MenuBuilder addItems(CompletableFuture<Stream<CrossSchemaResult>> stream);

    /**
     * Adds items from an async stream with custom conversion.
     *
     * @param stream the async stream of results
     * @param converter function to convert CrossSchemaResult to MenuItem
     * @return a new builder instance with converted stream results added
     * @throws IllegalArgumentException if stream or converter is null
     */
    MenuBuilder addItems(CompletableFuture<Stream<CrossSchemaResult>> stream, Function<CrossSchemaResult, MenuItem> converter);

    /**
     * Adds items from a paginated data-api page.
     * Uses default conversion from CrossSchemaResult to MenuItem.
     *
     * @param page the pagination page containing results
     * @return a new builder instance with page results added
     * @throws IllegalArgumentException if page is null
     */
    MenuBuilder addItems(PaginationSupport.Page<CrossSchemaResult> page);

    /**
     * Adds items from a paginated data-api page with custom conversion.
     *
     * @param page the pagination page containing results
     * @param converter function to convert CrossSchemaResult to MenuItem
     * @return a new builder instance with converted page results added
     * @throws IllegalArgumentException if page or converter is null
     */
    MenuBuilder addItems(PaginationSupport.Page<CrossSchemaResult> page, Function<CrossSchemaResult, MenuItem> converter);
    
    // ===== ADVANCED DATA-API INTEGRATION =====
    
    /**
     * Adds items from an AsyncResultStream with default conversion.
     * Enables real-time streaming of data with backpressure handling.
     *
     * @param asyncStream the async result stream
     * @return a new builder instance with streaming results configured
     * @throws IllegalArgumentException if asyncStream is null
     */
    MenuBuilder addItems(AsyncResultStream asyncStream);
    
    /**
     * Adds items from an AsyncResultStream with custom conversion.
     *
     * @param asyncStream the async result stream
     * @param converter function to convert CrossSchemaResult to MenuItem
     * @return a new builder instance with streaming results configured
     * @throws IllegalArgumentException if asyncStream or converter is null
     */
    MenuBuilder addItems(AsyncResultStream asyncStream, Function<CrossSchemaResult, MenuItem> converter);
    
    /**
     * Adds items using StreamingExecutor with advanced streaming configuration.
     * Provides full control over buffering, backpressure, and parallel processing.
     *
     * @param query the query to execute
     * @param streamingConfig configuration for streaming behavior
     * @return a new builder instance with streaming query configured
     * @throws IllegalArgumentException if query or streamingConfig is null
     */
    MenuBuilder addItemsStreaming(CrossSchemaQueryBuilder query, StreamingExecutor.StreamingConfig streamingConfig);
    
    /**
     * Adds items using StreamingExecutor with custom conversion and configuration.
     *
     * @param query the query to execute
     * @param streamingConfig configuration for streaming behavior
     * @param converter function to convert CrossSchemaResult to MenuItem
     * @return a new builder instance with streaming query configured
     * @throws IllegalArgumentException if any parameter is null
     */
    MenuBuilder addItemsStreaming(CrossSchemaQueryBuilder query, StreamingExecutor.StreamingConfig streamingConfig, Function<CrossSchemaResult, MenuItem> converter);
    
    /**
     * Configures cursor-based pagination for efficient sequential navigation.
     * More efficient than offset-based pagination for large datasets.
     *
     * @param cursorRequest the cursor pagination request
     * @return a new builder instance with cursor pagination configured
     * @throws IllegalArgumentException if cursorRequest is null
     */
    MenuBuilder configureCursorPagination(PaginationSupport.CursorPageRequest cursorRequest);
    
    /**
     * Configures offset-based pagination for random access navigation.
     * Suitable for smaller datasets or when total page count is needed.
     *
     * @param offsetRequest the offset pagination request
     * @return a new builder instance with offset pagination configured
     * @throws IllegalArgumentException if offsetRequest is null
     */
    MenuBuilder configureOffsetPagination(PaginationSupport.OffsetPageRequest offsetRequest);
    
    /**
     * Enables real-time data updates for dynamic menu content.
     * The menu will automatically refresh when underlying data changes.
     *
     * @param enabled true to enable real-time updates, false to disable
     * @return a new builder instance with real-time updates configured
     */
    MenuBuilder realTimeUpdates(boolean enabled);
    
    /**
     * Configures data refresh interval for dynamic content.
     *
     * @param intervalMs refresh interval in milliseconds (0 to disable periodic refresh)
     * @return a new builder instance with refresh interval configured
     * @throws IllegalArgumentException if intervalMs is negative
     */
    MenuBuilder refreshInterval(long intervalMs);
    
    /**
     * Configures memory management for large datasets.
     * Controls caching behavior and memory usage optimization.
     *
     * @param maxCacheSize maximum number of items to cache in memory
     * @param evictionPolicy policy for removing items from cache when full
     * @return a new builder instance with memory management configured
     * @throws IllegalArgumentException if maxCacheSize is negative
     */
    MenuBuilder memoryManagement(int maxCacheSize, String evictionPolicy);

    // ===== UTILITY SYSTEM INTEGRATION =====
    
    /**
     * Adds a utility to this menu by utility ID.
     * The utility will be looked up in the registered utilities and applied.
     *
     * @param utilityId the ID of the utility to add
     * @return a new builder instance with the utility added
     * @throws IllegalArgumentException if utilityId is null or unknown
     */
    MenuBuilder addUtility(String utilityId);
    
    /**
     * Adds a utility to this menu by utility ID with custom configuration.
     *
     * @param utilityId the ID of the utility to add
     * @param config the utility configuration
     * @return a new builder instance with the utility added
     * @throws IllegalArgumentException if utilityId is null or unknown
     */
    MenuBuilder addUtility(String utilityId, sh.harold.fulcrum.api.menu.util.UtilityConfig config);
    
    /**
     * Adds a utility instance directly to this menu.
     *
     * @param utility the utility instance to add
     * @return a new builder instance with the utility added
     * @throws IllegalArgumentException if utility is null
     */
    MenuBuilder addUtility(sh.harold.fulcrum.api.menu.util.MenuUtility utility);
    
    /**
     * Removes a utility from this menu by utility ID.
     *
     * @param utilityId the ID of the utility to remove
     * @return a new builder instance with the utility removed
     * @throws IllegalArgumentException if utilityId is null
     */
    MenuBuilder removeUtility(String utilityId);
    
    /**
     * Configures automatic utility detection and application.
     * When enabled, compatible utilities will be automatically applied.
     *
     * @param enabled true to enable automatic utilities, false to disable
     * @return a new builder instance with the setting applied
     */
    MenuBuilder autoUtilities(boolean enabled);

    /**
     * Validates the current builder state.
     *
     * @throws IllegalStateException if the builder is in an invalid state
     */
    void validate();

    /**
     * Builds the menu instance.
     * 
     * @return the constructed menu
     * @throws IllegalStateException if the builder is in an invalid state
     */
    Menu build();

    /**
     * Builds the menu instance asynchronously.
     * 
     * @return a CompletableFuture containing the constructed menu
     * @throws IllegalStateException if the builder is in an invalid state
     */
    CompletableFuture<Menu> buildAsync();

    /**
     * Creates a copy of this builder.
     * 
     * @return a new builder instance with the same state
     */
    MenuBuilder copy();

    /**
     * Resets the builder to its initial state.
     * 
     * @return a new builder instance in the initial state
     */
    MenuBuilder reset();
}