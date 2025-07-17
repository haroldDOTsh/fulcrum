package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.List;
import java.util.UUID;

/**
 * Interface for providing content to scoreboard modules.
 * Content providers are responsible for generating the lines of text
 * that will be displayed on the scoreboard for a specific module.
 * 
 * <p>Content providers can be:
 * <ul>
 *   <li>Static - returning fixed content</li>
 *   <li>Dynamic - returning content that changes over time</li>
 *   <li>Player-specific - returning different content for different players</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Static content
 * ContentProvider staticProvider = new StaticContentProvider(Arrays.asList(
 *     "&7Server: &aLobby",
 *     "&7Players: &a100/500"
 * ));
 * 
 * // Dynamic content
 * ContentProvider dynamicProvider = new DynamicContentProvider(() -> Arrays.asList(
 *     "&7Time: &a" + getCurrentTime(),
 *     "&7Players: &a" + getOnlinePlayerCount()
 * ));
 * }</pre>
 */
public interface ContentProvider {

    /**
     * Gets the content lines for display on the scoreboard.
     * This method is called whenever the module needs to be rendered.
     * 
     * @param playerId the UUID of the player for whom content is being generated
     * @return a list of content lines, or an empty list if no content
     */
    List<String> getContent(UUID playerId);

    /**
     * Checks if this content provider supports player-specific content.
     * If true, the provider may return different content for different players.
     * 
     * @return true if player-specific content is supported, false otherwise
     */
    default boolean isPlayerSpecific() {
        return false;
    }

    /**
     * Checks if this content provider is dynamic (content changes over time).
     * Dynamic providers may be refreshed periodically to update their content.
     * 
     * @return true if the content is dynamic, false for static content
     */
    default boolean isDynamic() {
        return false;
    }

    /**
     * Gets the refresh interval for dynamic content in milliseconds.
     * This is only relevant if {@link #isDynamic()} returns true.
     * 
     * @return the refresh interval in milliseconds, or -1 for no automatic refresh
     */
    default long getRefreshInterval() {
        return -1;
    }

    /**
     * Gets the maximum number of lines this provider can generate.
     * This helps with scoreboard line limit management.
     * 
     * @return the maximum number of lines, or -1 for no limit
     */
    default int getMaxLines() {
        return -1;
    }

    /**
     * Called when the content provider is initialized.
     * This is invoked when the provider is first used by a module.
     */
    default void onInitialize() {
        // Default implementation does nothing
    }

    /**
     * Called when the content provider is destroyed.
     * This is invoked when the provider is no longer needed.
     */
    default void onDestroy() {
        // Default implementation does nothing
    }

    /**
     * Called when the content needs to be refreshed.
     * This is invoked at the refresh interval or when manually triggered.
     * 
     * @param playerId the UUID of the player whose content is being refreshed
     */
    default void onRefresh(UUID playerId) {
        // Default implementation does nothing
    }

    /**
     * Checks if the content is currently available.
     * If false, the module may be skipped during rendering.
     * 
     * @param playerId the UUID of the player
     * @return true if content is available, false otherwise
     */
    default boolean isContentAvailable(UUID playerId) {
        return true;
    }

    /**
     * Gets a cache key for this content provider.
     * This is used for caching content when appropriate.
     * 
     * @param playerId the UUID of the player
     * @return a cache key, or null if caching is not supported
     */
    default String getCacheKey(UUID playerId) {
        return null;
    }

    /**
     * Gets the cache duration for this content provider in milliseconds.
     * This is only relevant if {@link #getCacheKey(UUID)} returns non-null.
     * 
     * @return the cache duration in milliseconds, or -1 for no caching
     */
    default long getCacheDuration() {
        return -1;
    }
}