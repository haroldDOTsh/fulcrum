package sh.harold.fulcrum.api.message.scoreboard.player;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.time.Duration;
import java.util.UUID;

/**
 * Interface for managing per-player scoreboard state.
 * This interface provides methods to manage individual player's scoreboard
 * configurations, custom titles, module overrides, and temporary flash states.
 *
 * <p>The player scoreboard manager is responsible for:
 * <ul>
 *   <li>Tracking which scoreboard each player is currently viewing</li>
 *   <li>Managing player-specific customizations (titles, module overrides)</li>
 *   <li>Handling flash functionality for temporary displays</li>
 *   <li>Maintaining player state across sessions</li>
 * </ul>
 */
public interface PlayerScoreboardManager {

    /**
     * Gets the current scoreboard state for a player.
     *
     * @param playerId the UUID of the player
     * @return the player's scoreboard state, or null if no state exists
     * @throws IllegalArgumentException if playerId is null
     */
    PlayerScoreboardState getPlayerState(UUID playerId);

    /**
     * Creates or updates the scoreboard state for a player.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard to show
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    void setPlayerScoreboard(UUID playerId, String scoreboardId);

    /**
     * Removes the scoreboard state for a player.
     * This will hide the scoreboard and clear any custom configurations.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void removePlayerScoreboard(UUID playerId);

    /**
     * Checks if a player has a scoreboard displayed.
     *
     * @param playerId the UUID of the player
     * @return true if the player has a scoreboard displayed, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasScoreboard(UUID playerId);

    /**
     * Gets the current scoreboard ID for a player.
     *
     * @param playerId the UUID of the player
     * @return the scoreboard ID, or null if no scoreboard is displayed
     * @throws IllegalArgumentException if playerId is null
     */
    String getCurrentScoreboardId(UUID playerId);

    /**
     * Sets a custom title for a player's scoreboard.
     *
     * @param playerId the UUID of the player
     * @param title    the custom title (supports color codes)
     * @throws IllegalArgumentException if playerId is null
     */
    void setPlayerTitle(UUID playerId, String title);

    /**
     * Gets the custom title for a player's scoreboard.
     *
     * @param playerId the UUID of the player
     * @return the custom title, or null if no custom title is set
     * @throws IllegalArgumentException if playerId is null
     */
    String getPlayerTitle(UUID playerId);

    /**
     * Removes the custom title for a player's scoreboard.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerTitle(UUID playerId);

    /**
     * Checks if a player has a custom title set.
     *
     * @param playerId the UUID of the player
     * @return true if the player has a custom title, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasCustomTitle(UUID playerId);

    /**
     * Adds a module override for a player.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module to override
     * @param enabled  whether the module should be enabled
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    void setModuleOverride(UUID playerId, String moduleId, boolean enabled);

    /**
     * Gets a module override for a player.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module to check
     * @return the module override, or null if no override exists
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    ModuleOverride getModuleOverride(UUID playerId, String moduleId);

    /**
     * Removes a module override for a player.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module override to remove
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    void removeModuleOverride(UUID playerId, String moduleId);

    /**
     * Checks if a player has a module override.
     *
     * @param playerId the UUID of the player
     * @param moduleId the ID of the module to check
     * @return true if the player has an override for the module, false otherwise
     * @throws IllegalArgumentException if playerId or moduleId is null
     */
    boolean hasModuleOverride(UUID playerId, String moduleId);

    /**
     * Starts a flash operation for a player.
     *
     * @param playerId    the UUID of the player
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param module      the module to flash
     * @param duration    the duration of the flash
     * @throws IllegalArgumentException if any parameter is null or duration is negative
     */
    void startFlash(UUID playerId, int moduleIndex, ScoreboardModule module, Duration duration);

    /**
     * Stops a flash operation for a player.
     *
     * @param playerId    the UUID of the player
     * @param moduleIndex the index of the module position to stop flashing
     * @throws IllegalArgumentException if playerId is null
     */
    void stopFlash(UUID playerId, int moduleIndex);

    /**
     * Checks if a player has an active flash at the given module index.
     *
     * @param playerId    the UUID of the player
     * @param moduleIndex the module index to check
     * @return true if there's an active flash at the module index, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasActiveFlash(UUID playerId, int moduleIndex);

    /**
     * Stops all active flashes for a player.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void stopAllFlashes(UUID playerId);

    /**
     * Marks a player's scoreboard as needing a refresh.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void markForRefresh(UUID playerId);

    /**
     * Checks if a player's scoreboard needs a refresh.
     *
     * @param playerId the UUID of the player
     * @return true if the scoreboard needs a refresh, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean needsRefresh(UUID playerId);

    /**
     * Clears the refresh flag for a player's scoreboard.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearRefreshFlag(UUID playerId);

    /**
     * Clears all data for a player.
     * This is typically called when a player disconnects.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerData(UUID playerId);

    /**
     * Gets the number of players currently using scoreboards.
     *
     * @return the number of players with active scoreboards
     */
    int getActivePlayerCount();
}