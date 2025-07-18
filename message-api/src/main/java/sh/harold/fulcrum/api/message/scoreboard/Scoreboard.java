package sh.harold.fulcrum.api.message.scoreboard;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.time.Duration;
import java.util.UUID;

/**
 * Main facade for the Scoreboard API, providing a simple interface for scoreboard operations.
 * This class follows the same pattern as the Message API, providing static methods for
 * common scoreboard operations while delegating to the injected ScoreboardService.
 * 
 * <p>Usage examples:
 * <pre>{@code
 * // Define a scoreboard with modules
 * Scoreboard.define("lobby")
 *     .title("&6&lLobby Server")
 *     .module(0, StatsModule.create())
 *     .module(1, RankModule.create())
 *     .register();
 * 
 * // Show scoreboard to player
 * Scoreboard.show(playerId, "lobby");
 * 
 * // Flash temporary content
 * Scoreboard.flash(playerId, 1, TempModule.create("&aWelcome!"), Duration.ofSeconds(5));
 * }</pre>
 */
public class Scoreboard {

    private static ScoreboardService scoreboardService;

    /**
     * Sets the ScoreboardService implementation to be used by this facade.
     * This method should be called during plugin initialization.
     * 
     * @param service the ScoreboardService implementation
     * @throws IllegalArgumentException if service is null
     */
    public static void setScoreboardService(ScoreboardService service) {
        if (service == null) {
            throw new IllegalArgumentException("ScoreboardService cannot be null");
        }
        scoreboardService = service;
    }

    /**
     * Gets the current ScoreboardService instance.
     * 
     * @return the ScoreboardService instance
     * @throws IllegalStateException if the service has not been initialized
     */
    public static ScoreboardService getService() {
        if (scoreboardService == null) {
            throw new IllegalStateException("ScoreboardService not initialized. Call Scoreboard.setScoreboardService() first.");
        }
        return scoreboardService;
    }

    /**
     * Creates a new ScoreboardBuilder for defining a scoreboard with the given ID.
     * 
     * @param scoreboardId the unique identifier for the scoreboard
     * @return a new ScoreboardBuilder instance
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public static ScoreboardBuilder define(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        return new ScoreboardBuilder(scoreboardId);
    }

    /**
     * Shows a registered scoreboard to a player.
     * 
     * @param playerId the UUID of the player
     * @param scoreboardId the ID of the scoreboard to show
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     * @throws IllegalStateException if the scoreboard is not registered
     */
    public static void show(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        getService().showScoreboard(playerId, scoreboardId);
    }

    /**
     * Hides the current scoreboard from a player.
     * 
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    public static void hide(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        getService().hideScoreboard(playerId);
    }

    /**
     * Flashes a temporary module to a player's scoreboard for a specified duration.
     * The module will be shown temporarily and then the previous content will be restored.
     *
     * @param playerId the UUID of the player
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param module the module to flash
     * @param duration the duration to show the module
     * @throws IllegalArgumentException if any parameter is null or duration is negative
     */
    public static void flash(UUID playerId, int moduleIndex, ScoreboardModule module, Duration duration) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }
        getService().flashModule(playerId, moduleIndex, module, duration);
    }

    /**
     * Sets a custom title for a specific player's scoreboard.
     * This overrides the default title for that player.
     * 
     * @param playerId the UUID of the player
     * @param title the custom title (supports color codes)
     * @throws IllegalArgumentException if playerId is null
     */
    public static void setPlayerTitle(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        getService().setPlayerTitle(playerId, title);
    }

    /**
     * Removes a custom title for a specific player, reverting to the default title.
     * 
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    public static void clearPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        getService().clearPlayerTitle(playerId);
    }

    /**
     * Checks if a scoreboard with the given ID is registered.
     * 
     * @param scoreboardId the ID of the scoreboard to check
     * @return true if the scoreboard is registered, false otherwise
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public static boolean isRegistered(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        return getService().isScoreboardRegistered(scoreboardId);
    }

    /**
     * Unregisters a scoreboard, removing it from the system.
     * Players currently viewing this scoreboard will have it hidden.
     * 
     * @param scoreboardId the ID of the scoreboard to unregister
     * @throws IllegalArgumentException if scoreboardId is null or empty
     */
    public static void unregister(String scoreboardId) {
        if (scoreboardId == null || scoreboardId.trim().isEmpty()) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null or empty");
        }
        getService().unregisterScoreboard(scoreboardId);
    }

    /**
     * Forces a refresh of a player's scoreboard, re-rendering all modules.
     * This is useful when module content has changed externally.
     * 
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    public static void refresh(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        getService().refreshPlayerScoreboard(playerId);
    }
}