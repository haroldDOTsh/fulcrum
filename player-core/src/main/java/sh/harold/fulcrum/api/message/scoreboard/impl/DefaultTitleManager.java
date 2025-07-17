package sh.harold.fulcrum.api.message.scoreboard.impl;

import sh.harold.fulcrum.api.message.scoreboard.render.TitleManager;
import sh.harold.fulcrum.api.message.scoreboard.util.ColorCodeProcessor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of TitleManager.
 * This class manages scoreboard titles including player-specific overrides.
 */
public class DefaultTitleManager implements TitleManager {

    private final ConcurrentHashMap<UUID, String> playerTitles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> playerScoreboardTitles = new ConcurrentHashMap<>();
    
    private String globalDefaultTitle = "&7Scoreboard";
    private int maxTitleLength = 32;
    private boolean variablesEnabled = true;

    @Override
    public String getEffectiveTitle(UUID playerId, String scoreboardId, String defaultTitle) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        // Check for player-specific scoreboard title first
        String playerScoreboardKey = playerId.toString() + ":" + scoreboardId;
        String playerScoreboardTitle = playerScoreboardTitles.get(playerScoreboardKey);
        if (playerScoreboardTitle != null) {
            return formatTitle(playerId, playerScoreboardTitle);
        }
        
        // Check for general player title
        String playerTitle = playerTitles.get(playerId);
        if (playerTitle != null) {
            return formatTitle(playerId, playerTitle);
        }
        
        // Use scoreboard default title
        if (defaultTitle != null && !defaultTitle.trim().isEmpty()) {
            return formatTitle(playerId, defaultTitle);
        }
        
        // Fall back to global default
        return formatTitle(playerId, globalDefaultTitle);
    }

    @Override
    public void setPlayerTitle(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        if (title == null) {
            playerTitles.remove(playerId);
        } else {
            playerTitles.put(playerId, title);
        }
    }

    @Override
    public void setPlayerScoreboardTitle(UUID playerId, String scoreboardId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        String key = playerId.toString() + ":" + scoreboardId;
        if (title == null) {
            playerScoreboardTitles.remove(key);
        } else {
            playerScoreboardTitles.put(key, title);
        }
    }

    @Override
    public String getPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        return playerTitles.get(playerId);
    }

    @Override
    public String getPlayerScoreboardTitle(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        String key = playerId.toString() + ":" + scoreboardId;
        return playerScoreboardTitles.get(key);
    }

    @Override
    public void clearPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        playerTitles.remove(playerId);
    }

    @Override
    public void clearPlayerScoreboardTitle(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        String key = playerId.toString() + ":" + scoreboardId;
        playerScoreboardTitles.remove(key);
    }

    @Override
    public boolean hasPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        return playerTitles.containsKey(playerId);
    }

    @Override
    public boolean hasPlayerScoreboardTitle(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        String key = playerId.toString() + ":" + scoreboardId;
        return playerScoreboardTitles.containsKey(key);
    }

    @Override
    public String formatTitle(UUID playerId, String rawTitle) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (rawTitle == null) {
            throw new IllegalArgumentException("Raw title cannot be null");
        }
        
        String processed = rawTitle;
        
        // Process variables if enabled
        if (variablesEnabled) {
            processed = processVariables(playerId, processed);
        }
        
        // Process color codes
        processed = ColorCodeProcessor.processLegacyCodes(processed);
        
        // Truncate if necessary
        if (ColorCodeProcessor.getStrippedLength(processed) > maxTitleLength) {
            processed = ColorCodeProcessor.truncateWithColors(processed, maxTitleLength);
        }
        
        return processed;
    }

    @Override
    public String getGlobalDefaultTitle() {
        return globalDefaultTitle;
    }

    @Override
    public void setGlobalDefaultTitle(String title) {
        this.globalDefaultTitle = title != null ? title : "&7Scoreboard";
    }

    @Override
    public boolean validateTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("Title cannot be null");
        }
        
        // Check length
        if (ColorCodeProcessor.getStrippedLength(title) > maxTitleLength) {
            return false;
        }
        
        // Check for illegal characters (this could be expanded)
        return !title.contains("\n") && !title.contains("\r");
    }

    @Override
    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    @Override
    public String truncateTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("Title cannot be null");
        }
        
        if (ColorCodeProcessor.getStrippedLength(title) <= maxTitleLength) {
            return title;
        }
        
        return ColorCodeProcessor.truncateWithColors(title, maxTitleLength);
    }

    @Override
    public void clearPlayerTitles(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        playerTitles.remove(playerId);
        
        // Remove all scoreboard-specific titles for this player
        String playerPrefix = playerId.toString() + ":";
        playerScoreboardTitles.entrySet().removeIf(entry -> entry.getKey().startsWith(playerPrefix));
    }

    @Override
    public int getCustomTitleCount() {
        return playerTitles.size() + playerScoreboardTitles.size();
    }

    @Override
    public boolean areVariablesEnabled() {
        return variablesEnabled;
    }

    @Override
    public void setVariablesEnabled(boolean enabled) {
        this.variablesEnabled = enabled;
    }

    @Override
    public String processVariables(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (title == null) {
            throw new IllegalArgumentException("Title cannot be null");
        }
        
        String processed = title;
        
        // Basic variable replacements
        processed = processed.replace("{player}", getPlayerName(playerId));
        processed = processed.replace("{server}", "Server"); // This could be configurable
        processed = processed.replace("{time}", getCurrentTime());
        
        return processed;
    }
    
    private String getPlayerName(UUID playerId) {
        // In a real implementation, this would get the player name from Bukkit
        // For now, return a placeholder
        return "Player";
    }
    
    private String getCurrentTime() {
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }
    
    /**
     * Sets the maximum title length.
     * 
     * @param maxLength the maximum length for titles
     */
    public void setMaxTitleLength(int maxLength) {
        this.maxTitleLength = Math.max(1, maxLength);
    }
}