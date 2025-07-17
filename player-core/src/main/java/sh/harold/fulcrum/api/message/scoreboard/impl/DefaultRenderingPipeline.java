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
    
    private int maxLines = 13;
    private String staticBottomLine = "&7play.example.com";
    private boolean blockSeparationEnabled = true;
    private String blockSeparationCharacter = " ";

    public DefaultRenderingPipeline(TitleManager titleManager, PlayerScoreboardManager playerManager) {
        this.titleManager = titleManager;
        this.playerManager = playerManager;
        this.colorProcessor = new ColorCodeProcessor();
    }

    @Override
    public RenderedScoreboard renderScoreboard(UUID playerId, ScoreboardDefinition definition) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }

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

        List<String> rawContent = new ArrayList<>();
        
        // Get player state for overrides and flash content
        PlayerScoreboardState playerState = playerManager.getPlayerState(playerId);
        
        // Collect content from modules in descending priority order
        Map<Integer, ScoreboardModule> modules = definition.getModulesDescending();
        
        // Add flash content first (highest priority)
        if (playerState != null) {
            Map<Integer, PlayerScoreboardState.FlashState> activeFlashes = playerState.getActiveFlashes();
            for (Map.Entry<Integer, PlayerScoreboardState.FlashState> entry : activeFlashes.entrySet()) {
                PlayerScoreboardState.FlashState flashState = entry.getValue();
                if (flashState != null && !flashState.isExpired()) {
                    ScoreboardModule module = flashState.getModule();
                    if (module != null) {
                        List<String> moduleContent = module.getContentProvider().getContent(playerId);
                        if (moduleContent != null) {
                            rawContent.addAll(moduleContent);
                        }
                    }
                }
            }
        }
        
        // Add regular modules (respecting overrides)
        for (Map.Entry<Integer, ScoreboardModule> entry : modules.entrySet()) {
            ScoreboardModule module = entry.getValue();
            if (module != null) {
                // Check if module is overridden for this player
                boolean moduleEnabled = true;
                if (playerState != null) {
                    ModuleOverride override = playerState.getModuleOverride(module.getModuleId());
                    moduleEnabled = (override == null) || override.isEnabled();
                }
                
                if (moduleEnabled) {
                    List<String> moduleContent = module.getContentProvider().getContent(playerId);
                    if (moduleContent != null && !moduleContent.isEmpty()) {
                        rawContent.addAll(moduleContent);
                    }
                }
            }
        }
        
        return processContent(playerId, rawContent);
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
        
        // Apply line limit
        processed = applyLineLimit(processed);
        
        return processed;
    }

    @Override
    public List<String> applyLineLimit(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        
        if (content.size() <= maxLines) {
            return new ArrayList<>(content);
        }
        
        return new ArrayList<>(content.subList(0, maxLines));
    }

    @Override
    public List<String> addBlockSeparations(List<String> content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        
        List<String> result = new ArrayList<>();
        boolean first = true;
        
        for (String line : content) {
            if (!first && !line.trim().isEmpty()) {
                result.add(blockSeparationCharacter);
            }
            result.add(line);
            first = false;
        }
        
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
                result.add(" ");
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