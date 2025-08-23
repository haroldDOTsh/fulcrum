package sh.harold.fulcrum.api.message.scoreboard.render;

import java.util.UUID;

/**
 * Version-agnostic interface for rendering scoreboard packets.
 * This interface abstracts the NMS (Net Minecraft Server) packet handling,
 * allowing different Minecraft versions to have their own implementations.
 *
 * <p>The PacketRenderer is responsible for:
 * <ul>
 *   <li>Converting rendered scoreboard data into Minecraft packets</li>
 *   <li>Sending packets to players</li>
 *   <li>Managing scoreboard display state</li>
 *   <li>Handling version-specific packet differences</li>
 * </ul>
 *
 * <p>This interface is typically implemented by version-specific adapters
 * that handle the actual NMS packet creation and transmission.
 */
public interface PacketRenderer {

    /**
     * Displays a rendered scoreboard to a player.
     * This method should create and send all necessary packets to show
     * the scoreboard to the player.
     *
     * @param playerId   the UUID of the player
     * @param scoreboard the rendered scoreboard to display
     * @throws IllegalArgumentException if playerId or scoreboard is null
     */
    void displayScoreboard(UUID playerId, RenderedScoreboard scoreboard);

    /**
     * Updates the content of a player's scoreboard without recreating it.
     * This is more efficient than displaying a new scoreboard when only
     * the content has changed.
     *
     * @param playerId   the UUID of the player
     * @param scoreboard the updated scoreboard content
     * @throws IllegalArgumentException if playerId or scoreboard is null
     */
    void updateScoreboard(UUID playerId, RenderedScoreboard scoreboard);

    /**
     * Updates only the title of a player's scoreboard.
     * This is the most efficient way to change just the title.
     *
     * @param playerId the UUID of the player
     * @param title    the new title
     * @throws IllegalArgumentException if playerId is null
     */
    void updateTitle(UUID playerId, String title);

    /**
     * Hides the scoreboard from a player.
     * This method should remove the scoreboard display without affecting
     * the player's state in the ScoreboardService.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void hideScoreboard(UUID playerId);

    /**
     * Checks if a player currently has a scoreboard displayed.
     * This checks the actual client-side display state, not the service state.
     *
     * @param playerId the UUID of the player
     * @return true if the player has a scoreboard displayed, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean hasScoreboardDisplayed(UUID playerId);

    /**
     * Forces a complete refresh of a player's scoreboard.
     * This recreates the entire scoreboard display, which can be useful
     * for fixing display issues or synchronization problems.
     *
     * @param playerId   the UUID of the player
     * @param scoreboard the scoreboard to refresh
     * @throws IllegalArgumentException if playerId or scoreboard is null
     */
    void refreshScoreboard(UUID playerId, RenderedScoreboard scoreboard);

    /**
     * Clears all scoreboard-related packets for a player.
     * This is typically called when a player disconnects or when
     * cleaning up player data.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearPlayerPackets(UUID playerId);

    /**
     * Gets the maximum number of characters allowed per line.
     * This varies by Minecraft version and affects content processing.
     *
     * @return the maximum characters per line
     */
    int getMaxCharactersPerLine();

    /**
     * Gets the maximum number of lines supported by this renderer.
     * This should typically return 15 (13 content lines + title + bottom).
     *
     * @return the maximum number of lines
     */
    int getMaxLines();

    /**
     * Checks if this renderer supports colored text.
     * Some older versions or configurations may not support colors.
     *
     * @return true if colored text is supported, false otherwise
     */
    boolean supportsColoredText();

    /**
     * Checks if this renderer supports custom titles.
     * Some implementations may have limited title support.
     *
     * @return true if custom titles are supported, false otherwise
     */
    boolean supportsCustomTitles();

    /**
     * Gets the version information for this renderer.
     * This is useful for debugging and version-specific behavior.
     *
     * @return the version information
     */
    String getVersionInfo();

    /**
     * Validates that a rendered scoreboard is compatible with this renderer.
     * This checks line counts, character limits, and other constraints.
     *
     * @param scoreboard the scoreboard to validate
     * @return true if the scoreboard is valid, false otherwise
     * @throws IllegalArgumentException if scoreboard is null
     */
    boolean validateScoreboard(RenderedScoreboard scoreboard);

    /**
     * Gets the number of players currently displaying scoreboards.
     * This is useful for monitoring and performance tracking.
     *
     * @return the number of players with active scoreboard displays
     */
    int getActiveDisplayCount();

    /**
     * Checks if packet batching is currently enabled.
     *
     * @return true if packet batching is enabled, false otherwise
     */
    boolean isPacketBatchingEnabled();

    /**
     * Enables or disables packet batching for performance optimization.
     * When enabled, multiple packet operations may be batched together.
     *
     * @param enabled whether packet batching should be enabled
     */
    void setPacketBatchingEnabled(boolean enabled);
}