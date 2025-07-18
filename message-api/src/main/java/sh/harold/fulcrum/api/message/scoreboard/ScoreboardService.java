package sh.harold.fulcrum.api.message.scoreboard;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;

import java.time.Duration;
import java.util.UUID;

/**
 * Core service interface for the Scoreboard API.
 * This interface defines the contract for all scoreboard operations and is implemented
 * by the actual scoreboard service in the player-core module.
 *
 * <p>The service handles:
 * <ul>
 *   <li>Registration and management of scoreboard definitions</li>
 *   <li>Per-player scoreboard state management</li>
 *   <li>Module rendering and display</li>
 *   <li>Flash functionality for temporary displays</li>
 *   <li>Title customization</li>
 * </ul>
 */
public interface ScoreboardService {

    /**
     * Registers a new scoreboard definition with the service.
     *
     * @param scoreboardId the unique identifier for the scoreboard
     * @param definition   the scoreboard definition containing modules and configuration
     * @throws IllegalArgumentException if scoreboardId is null/empty or definition is null
     * @throws IllegalStateException    if a scoreboard with the same ID is already registered
     */
    void registerScoreboard(String scoreboardId, ScoreboardDefinition definition);

    /**
     * Unregisters a scoreboard definition from the service.
     * Players currently viewing this scoreboard will have it hidden.
     *
     * @param scoreboardId the ID of the scoreboard to unregister
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    void unregisterScoreboard(String scoreboardId);

    /**
     * Checks if a scoreboard with the given ID is registered.
     *
     * @param scoreboardId the ID of the scoreboard to check
     * @return true if the scoreboard is registered, false otherwise
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    boolean isScoreboardRegistered(String scoreboardId);

    /**
     * Shows a registered scoreboard to a player.
     * This will render the scoreboard's modules and display them to the player.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard to show
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     * @throws IllegalStateException    if the scoreboard is not registered
     */
    void showScoreboard(UUID playerId, String scoreboardId);

    /**
     * Hides the current scoreboard from a player.
     * This will remove the scoreboard display but maintain the player's state.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void hideScoreboard(UUID playerId);

    /**
     * Flashes a temporary module to a player's scoreboard for a specified duration.
     * The module will be inserted at the specified moduleIndex and then removed after the duration.
     *
     * @param playerId    the UUID of the player
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param module      the module to flash
     * @param duration    the duration to show the module
     * @throws IllegalArgumentException if any parameter is null or duration is negative
     */
    void flashModule(UUID playerId, int moduleIndex, ScoreboardModule module, Duration duration);

    /**
     * Sets a custom title for a specific player's scoreboard.
     * This overrides the default title defined in the scoreboard definition.
     *
     * @param playerId the UUID of the player
     * @param title    the custom title (supports color codes)
     * @throws IllegalArgumentException if playerId is null
     */
    void setPlayerTitle(UUID playerId, String title);

    /**
     * Removes a custom title for a specific player, reverting to the default title.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerTitle(UUID playerId);

    /**
     * Forces a refresh of a player's scoreboard, re-rendering all modules.
     * This is useful when module content has changed externally.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void refreshPlayerScoreboard(UUID playerId);

    /**
     * Gets the current scoreboard ID that a player is viewing.
     *
     * @param playerId the UUID of the player
     * @return the scoreboard ID, or null if no scoreboard is displayed
     * @throws IllegalArgumentException if playerId is null
     */
    String getCurrentScoreboardId(UUID playerId);

    /**
     * Checks if a player currently has a scoreboard displayed.
     *
     * @param playerId the UUID of the player
     * @return true if the player has a scoreboard displayed, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasScoreboardDisplayed(UUID playerId);

    /**
     * Enables or disables module overrides for a specific player.
     * When enabled, the player can have module-specific customizations.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module to override
     * @param enabled  whether the override should be enabled
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    void setModuleOverride(UUID playerId, String moduleId, boolean enabled);

    /**
     * Checks if a module override is enabled for a specific player.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module to check
     * @return true if the module override is enabled, false otherwise
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    boolean isModuleOverrideEnabled(UUID playerId, String moduleId);

    /**
     * Clears all player-specific data when a player disconnects.
     * This includes custom titles, module overrides, and temporary states.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerData(UUID playerId);
}