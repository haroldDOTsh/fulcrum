package sh.harold.fulcrum.api.message.scoreboard.player;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Represents the scoreboard state for an individual player.
 * This class holds all player-specific scoreboard information including
 * the current scoreboard ID, custom title, module overrides, and flash states.
 *
 * <p>This class is thread-safe and can be safely accessed from multiple threads.
 */
public class PlayerScoreboardState {

    private final UUID playerId;
    private final Map<String, ModuleOverride> moduleOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, FlashState> activeFlashes = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> activeFlashTasks = new ConcurrentHashMap<>();
    private final long createdTime;
    private volatile String currentScoreboardId;
    private volatile String customTitle;
    private volatile boolean needsRefresh;
    private volatile long lastUpdated;

    /**
     * Creates a new PlayerScoreboardState for the given player.
     *
     * @param playerId the UUID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    public PlayerScoreboardState(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        this.playerId = playerId;
        this.createdTime = System.currentTimeMillis();
        this.lastUpdated = this.createdTime;
        this.needsRefresh = false;
    }

    /**
     * Gets the UUID of the player this state belongs to.
     *
     * @return the player UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Gets the current scoreboard ID for this player.
     *
     * @return the scoreboard ID, or null if no scoreboard is displayed
     */
    public String getCurrentScoreboardId() {
        return currentScoreboardId;
    }

    /**
     * Sets the current scoreboard ID for this player.
     *
     * @param scoreboardId the scoreboard ID to set
     */
    public void setCurrentScoreboardId(String scoreboardId) {
        this.currentScoreboardId = scoreboardId;
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Gets the custom title for this player's scoreboard.
     *
     * @return the custom title, or null if no custom title is set
     */
    public String getCustomTitle() {
        return customTitle;
    }

    /**
     * Sets the custom title for this player's scoreboard.
     *
     * @param title the custom title to set
     */
    public void setCustomTitle(String title) {
        this.customTitle = title;
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Checks if this player has a custom title set.
     *
     * @return true if a custom title is set, false otherwise
     */
    public boolean hasCustomTitle() {
        return customTitle != null && !customTitle.trim().isEmpty();
    }

    /**
     * Checks if this player has a scoreboard displayed.
     *
     * @return true if a scoreboard is displayed, false otherwise
     */
    public boolean hasScoreboard() {
        return currentScoreboardId != null;
    }

    /**
     * Gets a module override for the given module ID.
     *
     * @param moduleId the ID of the module
     * @return the module override, or null if no override exists
     * @throws IllegalArgumentException if moduleId is null
     */
    public ModuleOverride getModuleOverride(String moduleId) {
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        return moduleOverrides.get(moduleId);
    }

    /**
     * Sets a module override for the given module ID.
     *
     * @param moduleId the ID of the module
     * @param override the module override to set
     * @throws IllegalArgumentException if moduleId or override is null
     */
    public void setModuleOverride(String moduleId, ModuleOverride override) {
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        if (override == null) {
            throw new IllegalArgumentException("Module override cannot be null");
        }
        moduleOverrides.put(moduleId, override);
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Removes a module override for the given module ID.
     *
     * @param moduleId the ID of the module
     * @throws IllegalArgumentException if moduleId is null
     */
    public void removeModuleOverride(String moduleId) {
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        moduleOverrides.remove(moduleId);
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Checks if a module override exists for the given module ID.
     *
     * @param moduleId the ID of the module
     * @return true if an override exists, false otherwise
     * @throws IllegalArgumentException if moduleId is null
     */
    public boolean hasModuleOverride(String moduleId) {
        if (moduleId == null) {
            throw new IllegalArgumentException("Module ID cannot be null");
        }
        return moduleOverrides.containsKey(moduleId);
    }

    /**
     * Gets all module overrides for this player.
     *
     * @return a copy of the module overrides map
     */
    public Map<String, ModuleOverride> getModuleOverrides() {
        return new ConcurrentHashMap<>(moduleOverrides);
    }

    /**
     * Starts a flash operation at the given module index.
     *
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param module      the module to flash
     * @param duration    the duration of the flash
     * @throws IllegalArgumentException if module is null or duration is negative
     */
    public void startFlash(int moduleIndex, ScoreboardModule module, Duration duration) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }

        // Cancel any existing flash task at this module index
        stopFlash(moduleIndex);

        FlashState flashState = new FlashState(module, System.currentTimeMillis() + duration.toMillis());
        activeFlashes.put(moduleIndex, flashState);
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Starts a flash operation with a scheduled task for automatic expiration.
     *
     * @param moduleIndex   the index of the module position to replace (0-based)
     * @param module        the module to flash
     * @param duration      the duration of the flash
     * @param scheduledTask the scheduled task for automatic expiration
     * @throws IllegalArgumentException if module is null or duration is negative
     */
    public void startFlashWithTask(int moduleIndex, ScoreboardModule module, Duration duration, ScheduledFuture<?> scheduledTask) {
        if (module == null) {
            throw new IllegalArgumentException("Module cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }

        // Cancel any existing flash task at this module index
        stopFlash(moduleIndex);

        FlashState flashState = new FlashState(module, System.currentTimeMillis() + duration.toMillis());
        activeFlashes.put(moduleIndex, flashState);

        // Store the scheduled task for proper cleanup
        if (scheduledTask != null) {
            activeFlashTasks.put(moduleIndex, scheduledTask);
        }

        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Stops a flash operation at the given module index.
     *
     * @param moduleIndex the index of the module position to stop flashing
     */
    public void stopFlash(int moduleIndex) {
        activeFlashes.remove(moduleIndex);

        // Cancel any scheduled task for this flash
        ScheduledFuture<?> task = activeFlashTasks.remove(moduleIndex);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }

        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Gets the flash state at the given module index.
     *
     * @param moduleIndex the module index to check
     * @return the flash state, or null if no flash is active at this module index
     */
    public FlashState getFlashState(int moduleIndex) {
        FlashState state = activeFlashes.get(moduleIndex);
        if (state != null && state.isExpired()) {
            activeFlashes.remove(moduleIndex);
            return null;
        }
        return state;
    }

    /**
     * Checks if a flash is active at the given module index.
     *
     * @param moduleIndex the module index to check
     * @return true if a flash is active, false otherwise
     */
    public boolean hasActiveFlash(int moduleIndex) {
        return getFlashState(moduleIndex) != null;
    }

    /**
     * Stops all active flashes.
     */
    public void stopAllFlashes() {
        activeFlashes.clear();
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Gets all active flash states.
     *
     * @return a copy of the active flashes map
     */
    public Map<Integer, FlashState> getActiveFlashes() {
        // Remove expired flashes
        activeFlashes.entrySet().removeIf(entry -> entry.getValue().isExpired());
        return new ConcurrentHashMap<>(activeFlashes);
    }

    /**
     * Checks if this player's scoreboard needs a refresh.
     *
     * @return true if a refresh is needed, false otherwise
     */
    public boolean needsRefresh() {
        return needsRefresh;
    }

    /**
     * Marks this player's scoreboard as needing a refresh.
     */
    public void markForRefresh() {
        this.needsRefresh = true;
        this.lastUpdated = System.currentTimeMillis();
    }

    /**
     * Clears the refresh flag for this player's scoreboard.
     */
    public void clearRefreshFlag() {
        this.needsRefresh = false;
    }

    /**
     * Gets the time when this state was created.
     *
     * @return the creation time in milliseconds since epoch
     */
    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * Gets the time when this state was last updated.
     *
     * @return the last update time in milliseconds since epoch
     */
    public long getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Clears all state data for this player.
     */
    public void clear() {
        this.currentScoreboardId = null;
        this.customTitle = null;
        this.moduleOverrides.clear();
        this.activeFlashes.clear();
        this.needsRefresh = false;
        this.lastUpdated = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "PlayerScoreboardState{" +
                "playerId=" + playerId +
                ", currentScoreboardId='" + currentScoreboardId + '\'' +
                ", hasCustomTitle=" + hasCustomTitle() +
                ", moduleOverrides=" + moduleOverrides.size() +
                ", activeFlashes=" + activeFlashes.size() +
                ", needsRefresh=" + needsRefresh +
                '}';
    }

    /**
     * Represents the state of a flash operation.
     */
    public static class FlashState {
        private final ScoreboardModule module;
        private final long expirationTime;

        public FlashState(ScoreboardModule module, long expirationTime) {
            this.module = module;
            this.expirationTime = expirationTime;
        }

        public ScoreboardModule getModule() {
            return module;
        }

        public long getExpirationTime() {
            return expirationTime;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        @Override
        public String toString() {
            return "FlashState{" +
                    "module=" + module.getModuleId() +
                    ", expirationTime=" + expirationTime +
                    ", expired=" + isExpired() +
                    '}';
        }
    }
}