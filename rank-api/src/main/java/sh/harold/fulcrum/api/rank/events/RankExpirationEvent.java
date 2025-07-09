package sh.harold.fulcrum.api.rank.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;

import java.util.UUID;

/**
 * Event fired when a player's monthly rank expires.
 * This is specifically for time-limited monthly subscription ranks.
 */
public class RankExpirationEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final UUID playerId;
    private final MonthlyPackageRank expiredRank;
    private final long expiredAt;
    private final boolean wasAutoRenew;

    public RankExpirationEvent(UUID playerId, MonthlyPackageRank expiredRank, long expiredAt, boolean wasAutoRenew) {
        this.playerId = playerId;
        this.expiredRank = expiredRank;
        this.expiredAt = expiredAt;
        this.wasAutoRenew = wasAutoRenew;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public MonthlyPackageRank getExpiredRank() {
        return expiredRank;
    }

    public long getExpiredAt() {
        return expiredAt;
    }

    public boolean wasAutoRenew() {
        return wasAutoRenew;
    }

    /**
     * Check if this rank expired recently (within the last hour).
     */
    public boolean isRecentExpiration() {
        long hourAgo = System.currentTimeMillis() - (60 * 60 * 1000);
        return expiredAt > hourAgo;
    }

    /**
     * Get how long ago this rank expired in milliseconds.
     */
    public long getTimeSinceExpiration() {
        return System.currentTimeMillis() - expiredAt;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}