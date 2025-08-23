package sh.harold.fulcrum.api.message.scoreboard.render;

import java.util.UUID;

/**
 * Interface for managing scoreboard titles.
 * This interface handles title resolution, including default titles,
 * per-player custom titles, and title formatting.
 *
 * <p>The TitleManager is responsible for:
 * <ul>
 *   <li>Resolving the effective title for a player and scoreboard</li>
 *   <li>Managing per-player title overrides</li>
 *   <li>Formatting titles with color codes and variables</li>
 *   <li>Providing default titles when none are specified</li>
 * </ul>
 */
public interface TitleManager {

    /**
     * Gets the effective title for a player and scoreboard.
     * This method resolves the title hierarchy: player override > scoreboard default > global default.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard
     * @param defaultTitle the default title from the scoreboard definition
     * @return the effective title to display
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    String getEffectiveTitle(UUID playerId, String scoreboardId, String defaultTitle);

    /**
     * Sets a custom title for a specific player.
     * This overrides the default title for that player across all scoreboards.
     *
     * @param playerId the UUID of the player
     * @param title    the custom title to set
     * @throws IllegalArgumentException if playerId is null
     */
    void setPlayerTitle(UUID playerId, String title);

    /**
     * Sets a custom title for a specific player and scoreboard combination.
     * This overrides the default title for that player on a specific scoreboard.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard
     * @param title        the custom title to set
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    void setPlayerScoreboardTitle(UUID playerId, String scoreboardId, String title);

    /**
     * Gets the custom title for a specific player.
     *
     * @param playerId the UUID of the player
     * @return the custom title, or null if no custom title is set
     * @throws IllegalArgumentException if playerId is null
     */
    String getPlayerTitle(UUID playerId);

    /**
     * Gets the custom title for a specific player and scoreboard combination.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard
     * @return the custom title, or null if no custom title is set
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    String getPlayerScoreboardTitle(UUID playerId, String scoreboardId);

    /**
     * Clears the custom title for a specific player.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerTitle(UUID playerId);

    /**
     * Clears the custom title for a specific player and scoreboard combination.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    void clearPlayerScoreboardTitle(UUID playerId, String scoreboardId);

    /**
     * Checks if a player has a custom title set.
     *
     * @param playerId the UUID of the player
     * @return true if the player has a custom title, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasPlayerTitle(UUID playerId);

    /**
     * Checks if a player has a custom title set for a specific scoreboard.
     *
     * @param playerId     the UUID of the player
     * @param scoreboardId the ID of the scoreboard
     * @return true if the player has a custom title for the scoreboard, false otherwise
     * @throws IllegalArgumentException if playerId or scoreboardId is null
     */
    boolean hasPlayerScoreboardTitle(UUID playerId, String scoreboardId);

    /**
     * Formats a title with color codes and variables.
     * This method processes color codes and replaces any variables in the title.
     *
     * @param playerId the UUID of the player (for player-specific variables)
     * @param rawTitle the raw title to format
     * @return the formatted title
     * @throws IllegalArgumentException if playerId or rawTitle is null
     */
    String formatTitle(UUID playerId, String rawTitle);

    /**
     * Gets the global default title used when no other title is specified.
     *
     * @return the global default title
     */
    String getGlobalDefaultTitle();

    /**
     * Sets the global default title used when no other title is specified.
     *
     * @param title the new global default title
     */
    void setGlobalDefaultTitle(String title);

    /**
     * Validates that a title meets display requirements.
     * This checks length limits, character restrictions, and other constraints.
     *
     * @param title the title to validate
     * @return true if the title is valid, false otherwise
     * @throws IllegalArgumentException if title is null
     */
    boolean validateTitle(String title);

    /**
     * Gets the maximum allowed length for titles.
     *
     * @return the maximum title length
     */
    int getMaxTitleLength();

    /**
     * Truncates a title to the maximum allowed length if necessary.
     *
     * @param title the title to truncate
     * @return the truncated title
     * @throws IllegalArgumentException if title is null
     */
    String truncateTitle(String title);

    /**
     * Clears all custom titles for a player.
     * This is typically called when a player disconnects.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerTitles(UUID playerId);

    /**
     * Gets the number of players with custom titles.
     *
     * @return the number of players with custom titles
     */
    int getCustomTitleCount();

    /**
     * Checks if title variables are enabled.
     * When enabled, titles can contain variables like {player} or {server}.
     *
     * @return true if title variables are enabled, false otherwise
     */
    boolean areVariablesEnabled();

    /**
     * Enables or disables title variables.
     *
     * @param enabled whether title variables should be enabled
     */
    void setVariablesEnabled(boolean enabled);

    /**
     * Processes variables in a title for a specific player.
     * This replaces variables like {player}, {server}, etc. with actual values.
     *
     * @param playerId the UUID of the player
     * @param title    the title containing variables
     * @return the title with variables replaced
     * @throws IllegalArgumentException if playerId or title is null
     */
    String processVariables(UUID playerId, String title);
}