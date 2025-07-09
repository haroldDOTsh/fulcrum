package sh.harold.fulcrum.api.rank.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import sh.harold.fulcrum.api.rank.model.EffectiveRank;

import java.util.UUID;

/**
 * Event fired when a player's rank changes.
 * This includes changes to functional, package, or monthly ranks.
 */
public class RankChangeEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final UUID playerId;
    private final EffectiveRank oldRank;
    private final EffectiveRank newRank;
    private final RankChangeType changeType;

    public RankChangeEvent(UUID playerId, EffectiveRank oldRank, EffectiveRank newRank, RankChangeType changeType) {
        this.playerId = playerId;
        this.oldRank = oldRank;
        this.newRank = newRank;
        this.changeType = changeType;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public EffectiveRank getOldRank() {
        return oldRank;
    }

    public EffectiveRank getNewRank() {
        return newRank;
    }

    public RankChangeType getChangeType() {
        return changeType;
    }

    /**
     * Check if the effective display name changed.
     */
    public boolean hasDisplayNameChanged() {
        if (oldRank == null) return true;
        return !oldRank.getEffectiveDisplayName().equals(newRank.getEffectiveDisplayName());
    }

    /**
     * Check if the effective color changed.
     */
    public boolean hasColorChanged() {
        if (oldRank == null) return true;
        return !oldRank.getEffectiveColorCode().equals(newRank.getEffectiveColorCode());
    }

    /**
     * Check if the effective priority changed.
     */
    public boolean hasPriorityChanged() {
        if (oldRank == null) return true;
        return oldRank.getPriorityValue() != newRank.getPriorityValue();
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Type of rank change that occurred.
     */
    public enum RankChangeType {
        FUNCTIONAL_RANK_SET,
        FUNCTIONAL_RANK_REMOVED,
        PACKAGE_RANK_CHANGED,
        MONTHLY_RANK_GRANTED,
        MONTHLY_RANK_EXPIRED,
        MONTHLY_RANK_REMOVED
    }
}