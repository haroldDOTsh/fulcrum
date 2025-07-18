package sh.harold.fulcrum.api.message.scoreboard.util;

import sh.harold.fulcrum.api.message.scoreboard.module.ScoreboardModule;
import sh.harold.fulcrum.api.message.scoreboard.player.PlayerScoreboardManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Task for managing scoreboard flash functionality.
 * This class handles the temporary display of modules on a player's scoreboard
 * and automatic restoration of the previous content after the flash duration.
 * 
 * <p>Flash tasks are typically scheduled to run after a specified duration
 * to remove the temporary module and restore the normal scoreboard content.
 */
public class ScoreboardFlashTask implements Runnable {

    private final UUID playerId;
    private final int moduleIndex;
    private final ScoreboardModule flashModule;
    private final PlayerScoreboardManager playerManager;
    private final Duration duration;
    private final long createdTime;
    private volatile boolean cancelled;
    private volatile ScheduledFuture<?> scheduledFuture;
    private volatile Consumer<UUID> refreshCallback;

    /**
     * Creates a new ScoreboardFlashTask.
     *
     * @param playerId the UUID of the player
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param flashModule the module to flash
     * @param playerManager the player manager to use for cleanup
     * @param duration the duration of the flash
     * @throws IllegalArgumentException if any parameter is null or duration is negative
     */
    public ScoreboardFlashTask(UUID playerId, int moduleIndex, ScoreboardModule flashModule,
                              PlayerScoreboardManager playerManager, Duration duration) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (flashModule == null) {
            throw new IllegalArgumentException("Flash module cannot be null");
        }
        if (playerManager == null) {
            throw new IllegalArgumentException("Player manager cannot be null");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Duration cannot be null or negative");
        }

        this.playerId = playerId;
        this.moduleIndex = moduleIndex;
        this.flashModule = flashModule;
        this.playerManager = playerManager;
        this.duration = duration;
        this.createdTime = System.currentTimeMillis();
        this.cancelled = false;
    }

    @Override
    public void run() {
        if (cancelled) {
            return;
        }

        try {
            // Stop the flash operation
            playerManager.stopFlash(playerId, moduleIndex);
            
            // Mark the player's scoreboard for refresh to update the display
            playerManager.markForRefresh(playerId);
            
            // Trigger immediate refresh if callback is available
            if (refreshCallback != null) {
                refreshCallback.accept(playerId);
            }
            
        } catch (Exception e) {
            // Log the error but don't throw it to prevent scheduler issues
            System.err.println("Error during flash task execution for player " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Cancels the flash task.
     * This prevents the task from running and removes the flash immediately.
     */
    public void cancel() {
        this.cancelled = true;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        
        // Immediately stop the flash
        try {
            playerManager.stopFlash(playerId, moduleIndex);
            playerManager.markForRefresh(playerId);
            
            // Trigger immediate refresh if callback is available
            if (refreshCallback != null) {
                refreshCallback.accept(playerId);
            }
        } catch (Exception e) {
            System.err.println("Error during flash task cancellation for player " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Gets the UUID of the player this task is for.
     *
     * @return the player UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Gets the module index of the flash module.
     *
     * @return the module index
     */
    public int getModuleIndex() {
        return moduleIndex;
    }

    /**
     * Gets the flash module.
     * 
     * @return the flash module
     */
    public ScoreboardModule getFlashModule() {
        return flashModule;
    }

    /**
     * Gets the duration of the flash.
     * 
     * @return the duration
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Gets the time when this task was created.
     * 
     * @return the creation time in milliseconds since epoch
     */
    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * Checks if this task has been cancelled.
     * 
     * @return true if the task is cancelled, false otherwise
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Gets the remaining time until the flash expires.
     * 
     * @return the remaining time in milliseconds, or 0 if expired
     */
    public long getRemainingTime() {
        long elapsed = System.currentTimeMillis() - createdTime;
        long remaining = duration.toMillis() - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * Checks if the flash has expired.
     * 
     * @return true if the flash has expired, false otherwise
     */
    public boolean isExpired() {
        return getRemainingTime() <= 0;
    }

    /**
     * Sets the scheduled future for this task.
     * This is used for proper cancellation handling.
     *
     * @param future the scheduled future
     */
    public void setScheduledFuture(ScheduledFuture<?> future) {
        this.scheduledFuture = future;
    }
    
    /**
     * Sets a callback to trigger immediate refresh when the flash expires.
     *
     * @param callback the refresh callback
     */
    public void setRefreshCallback(Consumer<UUID> callback) {
        this.refreshCallback = callback;
    }

    /**
     * Gets the scheduled future for this task.
     * 
     * @return the scheduled future, or null if not set
     */
    public ScheduledFuture<?> getScheduledFuture() {
        return scheduledFuture;
    }

    /**
     * Checks if this task is scheduled for execution.
     * 
     * @return true if the task is scheduled, false otherwise
     */
    public boolean isScheduled() {
        return scheduledFuture != null && !scheduledFuture.isDone();
    }

    /**
     * Gets the delay until the task executes.
     * 
     * @param unit the time unit for the delay
     * @return the delay in the specified unit
     * @throws IllegalStateException if the task is not scheduled
     */
    public long getDelay(TimeUnit unit) {
        if (scheduledFuture == null) {
            throw new IllegalStateException("Task is not scheduled");
        }
        return scheduledFuture.getDelay(unit);
    }

    /**
     * Creates a new flash task for the given parameters.
     *
     * @param playerId the UUID of the player
     * @param moduleIndex the index of the module position to replace (0-based)
     * @param flashModule the module to flash
     * @param playerManager the player manager to use for cleanup
     * @param duration the duration of the flash
     * @return a new ScoreboardFlashTask
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public static ScoreboardFlashTask create(UUID playerId, int moduleIndex, ScoreboardModule flashModule,
                                           PlayerScoreboardManager playerManager, Duration duration) {
        return new ScoreboardFlashTask(playerId, moduleIndex, flashModule, playerManager, duration);
    }

    @Override
    public String toString() {
        return "ScoreboardFlashTask{" +
                "playerId=" + playerId +
                ", moduleIndex=" + moduleIndex +
                ", flashModule=" + flashModule.getModuleId() +
                ", duration=" + duration +
                ", cancelled=" + cancelled +
                ", expired=" + isExpired() +
                ", remainingTime=" + getRemainingTime() +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ScoreboardFlashTask that = (ScoreboardFlashTask) obj;
        return moduleIndex == that.moduleIndex &&
                playerId.equals(that.playerId) &&
                flashModule.getModuleId().equals(that.flashModule.getModuleId());
    }

    @Override
    public int hashCode() {
        int result = playerId.hashCode();
        result = 31 * result + moduleIndex;
        result = 31 * result + flashModule.getModuleId().hashCode();
        return result;
    }
}