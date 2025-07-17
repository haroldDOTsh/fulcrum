package sh.harold.fulcrum.api.message.scoreboard.render;

import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;

import java.util.List;
import java.util.UUID;

/**
 * Core interface for the scoreboard rendering pipeline.
 * This interface defines the contract for rendering scoreboard content,
 * managing the 13-line limit, and handling content processing.
 * 
 * <p>The rendering pipeline is responsible for:
 * <ul>
 *   <li>Collecting content from all modules</li>
 *   <li>Applying player-specific overrides and customizations</li>
 *   <li>Enforcing the 13-line limit with truncation</li>
 *   <li>Processing color codes and formatting</li>
 *   <li>Adding block separations and static content</li>
 * </ul>
 */
public interface RenderingPipeline {

    /**
     * Renders a complete scoreboard for a player.
     * This method coordinates the entire rendering process from content collection
     * to final packet generation.
     * 
     * @param playerId the UUID of the player
     * @param definition the scoreboard definition to render
     * @return the rendered scoreboard content
     * @throws IllegalArgumentException if playerId or definition is null
     */
    RenderedScoreboard renderScoreboard(UUID playerId, ScoreboardDefinition definition);

    /**
     * Renders only the content lines for a scoreboard without packet generation.
     * This is useful for testing or when only the content is needed.
     * 
     * @param playerId the UUID of the player
     * @param definition the scoreboard definition to render
     * @return the rendered content lines
     * @throws IllegalArgumentException if playerId or definition is null
     */
    List<String> renderContent(UUID playerId, ScoreboardDefinition definition);

    /**
     * Renders the title for a scoreboard, applying any player-specific overrides.
     * 
     * @param playerId the UUID of the player
     * @param definition the scoreboard definition
     * @return the rendered title
     * @throws IllegalArgumentException if playerId or definition is null
     */
    String renderTitle(UUID playerId, ScoreboardDefinition definition);

    /**
     * Processes a list of content lines, applying formatting and enforcing limits.
     * 
     * @param playerId the UUID of the player
     * @param rawContent the raw content lines
     * @return the processed content lines
     * @throws IllegalArgumentException if playerId or rawContent is null
     */
    List<String> processContent(UUID playerId, List<String> rawContent);

    /**
     * Applies the 13-line limit to content, truncating if necessary.
     * 
     * @param content the content to limit
     * @return the limited content
     * @throws IllegalArgumentException if content is null
     */
    List<String> applyLineLimit(List<String> content);

    /**
     * Adds block separations between module content.
     * 
     * @param content the content to add separations to
     * @return the content with separations added
     * @throws IllegalArgumentException if content is null
     */
    List<String> addBlockSeparations(List<String> content);

    /**
     * Adds the static bottom line (typically server IP).
     * 
     * @param content the content to add the bottom line to
     * @return the content with the bottom line added
     * @throws IllegalArgumentException if content is null
     */
    List<String> addStaticBottomLine(List<String> content);

    /**
     * Processes color codes in the given text.
     * 
     * @param text the text to process
     * @return the text with processed color codes
     * @throws IllegalArgumentException if text is null
     */
    String processColorCodes(String text);

    /**
     * Validates that the rendered content meets scoreboard requirements.
     * 
     * @param content the content to validate
     * @return true if the content is valid, false otherwise
     * @throws IllegalArgumentException if content is null
     */
    boolean validateContent(List<String> content);

    /**
     * Gets the maximum number of lines that can be displayed on a scoreboard.
     * 
     * @return the maximum line count
     */
    int getMaxLines();

    /**
     * Gets the static bottom line text.
     * 
     * @return the static bottom line text
     */
    String getStaticBottomLine();

    /**
     * Sets the static bottom line text.
     * 
     * @param bottomLine the new static bottom line text
     */
    void setStaticBottomLine(String bottomLine);

    /**
     * Checks if block separations are enabled.
     * 
     * @return true if block separations are enabled, false otherwise
     */
    boolean isBlockSeparationEnabled();

    /**
     * Enables or disables block separations.
     * 
     * @param enabled whether block separations should be enabled
     */
    void setBlockSeparationEnabled(boolean enabled);

    /**
     * Gets the character used for block separations.
     * 
     * @return the block separation character
     */
    String getBlockSeparationCharacter();

    /**
     * Sets the character used for block separations.
     * 
     * @param character the new block separation character
     */
    void setBlockSeparationCharacter(String character);
}