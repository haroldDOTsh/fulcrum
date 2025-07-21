package sh.harold.fulcrum.api.message.scoreboard.impl;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.player.ModuleOverride;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardState;
import sh.harold.fulcrum.api.message.scoreboard.registry.ScoreboardDefinition;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;
import sh.harold.fulcrum.api.message.scoreboard.render.TitleManager;
import sh.harold.fulcrum.api.message.scoreboard.util.ColorCodeProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation of the RenderingPipeline interface.
 * This implementation handles the complete rendering process for scoreboards,
 * including content collection, processing, and packet generation.
 */
public class DefaultRenderingPipeline implements RenderingPipeline {

    private final TitleManager titleManager;
    private final PlayerScoreboardManager playerManager;
    private final ColorCodeProcessor colorProcessor;

    private int maxLines = 15;
    private String staticBottomLine = "&eplay.harold.sh"; // Default static bottom line
    private boolean blockSeparationEnabled = true;
    private String blockSeparationCharacter = "§r"; // Kept for backward compatibility
    private int separatorCounter = 0;

    public DefaultRenderingPipeline(TitleManager titleManager, PlayerScoreboardManager playerManager) {
        this.titleManager = titleManager;
        this.playerManager = playerManager;
        this.colorProcessor = new ColorCodeProcessor();
    }

    /**
     * Generates a unique separator using incremental spaces to ensure each separator
     * has unique content for Minecraft's scoreboard system.
     */
    private String generateUniqueSeparator() {
        if (separatorCounter == 0) {
            separatorCounter++;
            return ""; // First separator: empty string
        }

        StringBuilder separator = new StringBuilder();
        for (int i = 0; i < separatorCounter; i++) {
            separator.append(" ");
        }
        separatorCounter++;
        return separator.toString();
    }

    /**
     * Resets the separator counter for a new rendering cycle.
     */
    private void resetSeparatorCounter() {
        separatorCounter = 0;
    }

    @Override
    public RenderedScoreboard renderScoreboard(UUID playerId, ScoreboardDefinition definition) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }

        // Reset separator counter for new rendering cycle
        resetSeparatorCounter();

        String title = renderTitle(playerId, definition);
        List<String> content = renderContent(playerId, definition);

        int originalLineCount = content.size();
        boolean wasTruncated = originalLineCount > maxLines;

        return new RenderedScoreboard(playerId, definition.getScoreboardId(), title, content, originalLineCount, wasTruncated);
    }

    @Override
    public List<String> renderContent(UUID playerId, ScoreboardDefinition definition) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }

        // Reset separator counter for new rendering cycle
        resetSeparatorCounter();

        List<List<String>> moduleBlocks = new ArrayList<>();

        // Get player state for overrides and flash content
        PlayerScoreboardState playerState = playerManager.getPlayerState(playerId);

        // Collect content from modules in insertion order
        List<ScoreboardModule> modules = definition.getModules();



        // Process modules in insertion order, with flash replacements
        for (int i = 0; i < modules.size(); i++) {
            ScoreboardModule module = modules.get(i);
            if (module != null) {
                // Check if there's a flash replacement for this module index
                ScoreboardModule effectiveModule = module;
                if (playerState != null) {
                    Map<Integer, PlayerScoreboardState.FlashState> activeFlashes = playerState.getActiveFlashes();
                    PlayerScoreboardState.FlashState flashState = activeFlashes.get(i);
                    if (flashState != null && !flashState.isExpired()) {
                        effectiveModule = flashState.getModule();

                    }
                }

                // Check if module is overridden for this player
                boolean moduleEnabled = true;
                if (playerState != null) {
                    ModuleOverride override = playerState.getModuleOverride(effectiveModule.getModuleId());
                    moduleEnabled = (override == null) || override.isEnabled();
                }

                if (moduleEnabled) {
                    List<String> moduleContent = effectiveModule.getContentProvider().getContent(playerId);
                    if (moduleContent != null && !moduleContent.isEmpty()) {
                        moduleBlocks.add(new ArrayList<>(moduleContent));
                    }
                }
            }
        }

        List<String> processedContent = processModuleBlocks(playerId, moduleBlocks);
        
        return processedContent;
    }

    /**
     * Processes module blocks by adding proper separations between modules
     * and handling color codes for each line.
     */
    private List<String> processModuleBlocks(UUID playerId, List<List<String>> moduleBlocks) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleBlocks == null) {
            throw new IllegalArgumentException("Module blocks cannot be null");
        }

        List<String> processed = new ArrayList<>();

        // Process each module block
        for (int i = 0; i < moduleBlocks.size(); i++) {
            List<String> moduleContent = moduleBlocks.get(i);

            // Add separator between modules (but not before the first module)
            if (i > 0 && blockSeparationEnabled) {
                processed.add(generateUniqueSeparator());
            }

            // Process color codes for each line in the module
            for (String line : moduleContent) {
                if (line != null) {
                    processed.add(processColorCodes(line));
                }
            }
        }

        // Add static bottom line
        processed = addStaticBottomLine(processed);

        // Apply line limit (reserve last line for static content)
        processed = applyLineLimit(processed);

        return processed;
    }

    @Override
    public String renderTitle(UUID playerId, ScoreboardDefinition definition) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }

        String title = titleManager.getEffectiveTitle(playerId, definition.getScoreboardId(), definition.getEffectiveTitle());
        return processColorCodes(title);
    }

    @Override
    public List<String> processContent(UUID playerId, List<String> rawContent) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (rawContent == null) {
            throw new IllegalArgumentException("Raw content cannot be null");
        }

        // Reset separator counter for new processing cycle
        resetSeparatorCounter();

        List<String> processed = new ArrayList<>();

        // Process color codes for each line
        for (String line : rawContent) {
            if (line != null) {
                processed.add(processColorCodes(line));
            }
        }

        // Add block separations
        if (blockSeparationEnabled) {
            processed = addBlockSeparations(processed);
        }

        // Add static bottom line
        processed = addStaticBottomLine(processed);

        // Apply line limit (reserve last line for static content)
        processed = applyLineLimit(processed);

        return processed;
    }

    @Override
    public List<String> applyLineLimit(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        // Use 14 lines (reserve line 15 for static content)
        int usableLines = maxLines - 1;

        if (content.size() <= usableLines) {
            return new ArrayList<>(content);
        }

        return new ArrayList<>(content.subList(0, usableLines));
    }

    @Override
    public List<String> addBlockSeparations(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }


        if (!blockSeparationEnabled) {
            return new ArrayList<>(content);
        }

        // This method is now primarily used for backward compatibility
        // The new processModuleBlocks method handles proper module separation
        // For legacy content that doesn't use module blocks, just return as-is
        List<String> result = new ArrayList<>(content);


        return result;
    }

    @Override
    public List<String> addStaticBottomLine(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        List<String> result = new ArrayList<>(content);

        if (staticBottomLine != null && !staticBottomLine.trim().isEmpty()) {
            // Add separator if there's existing content
            if (!result.isEmpty()) {
                result.add(generateUniqueSeparator());
            }
            result.add(processColorCodes(staticBottomLine));
        }

        return result;
    }

    @Override
    public String processColorCodes(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        return ColorCodeProcessor.processLegacyCodes(text);
    }

    @Override
    public boolean validateContent(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        // Check line count
        if (content.size() > maxLines) {
            return false;
        }

        // Check each line for validity
        for (String line : content) {
            if (line == null) {
                return false;
            }

            // Check line length (this would be implementation-specific)
            if (line.length() > 40) { // Example limit
                return false;
            }
        }

        return true;
    }

    @Override
    public int getMaxLines() {
        return maxLines;
    }

    @Override
    public String getStaticBottomLine() {
        return staticBottomLine;
    }

    @Override
    public void setStaticBottomLine(String bottomLine) {
        this.staticBottomLine = bottomLine;
    }

    @Override
    public boolean isBlockSeparationEnabled() {
        return blockSeparationEnabled;
    }

    @Override
    public void setBlockSeparationEnabled(boolean enabled) {
        this.blockSeparationEnabled = enabled;
    }

    @Override
    public String getBlockSeparationCharacter() {
        return blockSeparationCharacter;
    }

    @Override
    public void setBlockSeparationCharacter(String character) {
        this.blockSeparationCharacter = character;
    }
}