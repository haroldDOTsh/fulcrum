package sh.harold.fulcrum.api.message.scoreboard.impl;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.player.ModuleOverride;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardState;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of PlayerScoreboardManager.
 * This class manages per-player scoreboard state using thread-safe operations.
 */
public class DefaultPlayerScoreboardManager implements PlayerScoreboardManager {

    private final ConcurrentHashMap<UUID, PlayerScoreboardState> playerStates = new ConcurrentHashMap<>();

    @Override
    public PlayerScoreboardState getPlayerState(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        return playerStates.get(playerId);
    }

    @Override
    public void setPlayerScoreboard(UUID playerId, String scoreboardId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboardId == null) {
            throw new IllegalArgumentException("Scoreboard ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.computeIfAbsent(playerId, PlayerScoreboardState::new);
        state.setCurrentScoreboardId(scoreboardId);
        state.markForRefresh();
    }

    @Override
    public void removePlayerScoreboard(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        playerStates.remove(playerId);
    }

    @Override
    public boolean hasScoreboard(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null && state.hasScoreboard();
    }

    @Override
    public String getCurrentScoreboardId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null ? state.getCurrentScoreboardId() : null;
    }

    @Override
    public void setPlayerTitle(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.computeIfAbsent(playerId, PlayerScoreboardState::new);
        state.setCustomTitle(title);
        state.markForRefresh();
    }

    @Override
    public String getPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null ? state.getCustomTitle() : null;
    }

    @Override
    public void clearPlayerTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.setCustomTitle(null);
            state.markForRefresh();
        }
    }

    @Override
    public boolean hasCustomTitle(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null && state.hasCustomTitle();
    }

    @Override
    public void setModuleOverride(UUID playerId, String moduleId, boolean enabled) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.computeIfAbsent(playerId, PlayerScoreboardState::new);
        ModuleOverride override = new ModuleOverride(moduleId, enabled);
        state.setModuleOverride(moduleId, override);
        state.markForRefresh();
    }

    @Override
    public ModuleOverride getModuleOverride(UUID playerId, String moduleId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null ? state.getModuleOverride(moduleId) : null;
    }

    @Override
    public void removeModuleOverride(UUID playerId, String moduleId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.removeModuleOverride(moduleId);
            state.markForRefresh();
        }
    }

    @Override
    public boolean hasModuleOverride(UUID playerId, String moduleId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null && state.hasModuleOverride(moduleId);
    }

    @Override
    public void startFlash(UUID playerId, int priority, ScoreboardModule module, Duration duration) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }
        
        PlayerScoreboardState state = playerStates.computeIfAbsent(playerId, PlayerScoreboardState::new);
        state.startFlash(priority, module, duration);
        state.markForRefresh();
    }

    @Override
    public void stopFlash(UUID playerId, int priority) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.stopFlash(priority);
            state.markForRefresh();
        }
    }

    @Override
    public boolean hasActiveFlash(UUID playerId, int priority) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null && state.hasActiveFlash(priority);
    }

    @Override
    public void stopAllFlashes(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.stopAllFlashes();
            state.markForRefresh();
        }
    }

    @Override
    public void markForRefresh(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.markForRefresh();
        }
    }

    @Override
    public boolean needsRefresh(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        return state != null && state.needsRefresh();
    }

    @Override
    public void clearRefreshFlag(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        
        PlayerScoreboardState state = playerStates.get(playerId);
        if (state != null) {
            state.clearRefreshFlag();
        }
    }

    @Override
    public void clearPlayerData(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        playerStates.remove(playerId);
    }

    @Override
    public int getActivePlayerCount() {
        return playerStates.size();
    }

    /**
     * Clears all player data. This should only be called during shutdown.
     */
    public void clearAllPlayerData() {
        playerStates.clear();
    }
}