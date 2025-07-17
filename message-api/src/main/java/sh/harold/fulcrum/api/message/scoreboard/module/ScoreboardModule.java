package sh.harold.fulcrum.api.message.scoreboard.module;

import java.util.List;
import java.util.UUID;

/**
 * Interface for individual scoreboard modules.
 * A module represents a section of content that can be displayed on a scoreboard.
 * Each module has a unique identifier and provides content through a ContentProvider.
 * 
 * <p>Modules are the building blocks of scoreboards and can be:
 * <ul>
 *   <li>Static - displaying fixed content</li>
 *   <li>Dynamic - displaying content that changes over time</li>
 *   <li>Player-specific - displaying different content for different players</li>
 * </ul>
 * 
 * <p>Example implementations:
 * <pre>{@code
 * public class StatsModule implements ScoreboardModule {
 *     @Override
 *     public String getModuleId() {
 *         return "stats";
 *     }
 *     
 *     @Override
 *     public ContentProvider getContentProvider() {
 *         return new DynamicContentProvider(() -> Arrays.asList(
 *             "&7Players: &a" + getOnlinePlayerCount(),
 *             "&7Rank: &6" + getPlayerRank()
 *         ));
 *     }
 * }
 * }</pre>
 */
public interface ScoreboardModule {

    /**
     * Gets the unique identifier for this module.
     * This ID is used for module overrides and configuration.
     * 
     * @return the module identifier
     */
    String getModuleId();

    /**
     * Gets the content provider for this module.
     * The content provider is responsible for generating the lines of content
     * that will be displayed on the scoreboard.
     * 
     * @return the content provider
     */
    ContentProvider getContentProvider();

    /**
     * Gets the display name for this module.
     * This is used for administrative purposes and debugging.
     * 
     * @return the display name, or the module ID if not overridden
     */
    default String getDisplayName() {
        return getModuleId();
    }

    /**
     * Checks if this module is enabled for the given player.
     * This allows for player-specific module filtering.
     * 
     * @param playerId the UUID of the player
     * @return true if the module should be displayed for this player, false otherwise
     */
    default boolean isEnabledFor(UUID playerId) {
        return true;
    }

    /**
     * Gets the maximum number of lines this module can display.
     * This helps with scoreboard line limit management.
     * 
     * @return the maximum number of lines, or -1 for no limit
     */
    default int getMaxLines() {
        return -1;
    }

    /**
     * Gets the refresh interval for this module in milliseconds.
     * This determines how often dynamic content should be updated.
     * 
     * @return the refresh interval in milliseconds, or -1 for no automatic refresh
     */
    default long getRefreshInterval() {
        return -1;
    }

    /**
     * Called when the module is initialized.
     * This is invoked when the module is first registered with a scoreboard.
     */
    default void onInitialize() {
        // Default implementation does nothing
    }

    /**
     * Called when the module is destroyed.
     * This is invoked when the module is removed from a scoreboard or the scoreboard is unregistered.
     */
    default void onDestroy() {
        // Default implementation does nothing
    }

    /**
     * Called when the module content needs to be refreshed.
     * This is invoked at the refresh interval or when manually triggered.
     * 
     * @param playerId the UUID of the player whose content is being refreshed
     */
    default void onRefresh(UUID playerId) {
        // Default implementation does nothing
    }

    /**
     * Gets player-specific content for this module.
     * This allows modules to display different content for different players.
     * 
     * @param playerId the UUID of the player
     * @return the content lines for the player, or null to use default content
     */
    default List<String> getPlayerContent(UUID playerId) {
        return null;
    }

    /**
     * Checks if this module supports player-specific content.
     * 
     * @return true if the module supports player-specific content, false otherwise
     */
    default boolean supportsPlayerContent() {
        return false;
    }

    /**
     * Gets the priority hint for this module.
     * This is used when modules are automatically ordered.
     * Higher values indicate higher priority.
     * 
     * @return the priority hint
     */
    default int getPriorityHint() {
        return 0;
    }
}