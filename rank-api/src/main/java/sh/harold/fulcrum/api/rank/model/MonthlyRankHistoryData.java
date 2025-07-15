package sh.harold.fulcrum.api.rank.model;

import sh.harold.fulcrum.api.data.annotation.*;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;

import java.util.UUID;

/**
 * Historical data schema for time-limited monthly ranks.
 * This table stores all monthly rank grants as separate records to maintain history.
 * Each grant creates a new record instead of overwriting the previous one.
 *
 * Performance indexes are optimized for the most common query patterns:
 * - Player-specific history queries (uuid)
 * - Active rank lookups (uuid + expiresAt)
 * - Historical ordering (grantedAt)
 * - Expiration cleanup (expiresAt)
 * - Rank-specific expiration queries (rank + expiresAt)
 */
@Table("monthly_ranks_history")
@SchemaVersion(1)
@Indexes({
    @CompositeIndex(
        name = "idx_uuid_expires",
        fields = {"uuid", "expiresAt"},
        orders = {IndexOrder.ASC, IndexOrder.DESC}
    ),
    @CompositeIndex(
        name = "idx_uuid_granted",
        fields = {"uuid", "grantedAt"},
        orders = {IndexOrder.ASC, IndexOrder.DESC}
    ),
    @CompositeIndex(
        name = "idx_rank_expires_at",
        fields = {"rank", "expiresAt"},
        orders = {IndexOrder.ASC, IndexOrder.DESC}
    )
})
public class MonthlyRankHistoryData {

    @Column(primary = true, generation = PrimaryKeyGeneration.RANDOM_UUID)
    public UUID id;

    @Index(name = "idx_player_uuid")  // For player-specific queries
    public UUID uuid;

    @Index(name = "idx_rank")  // For rank-specific queries across all players
    public MonthlyPackageRank rank;
    
    @Index(name = "idx_expires_at", order = IndexOrder.DESC)  // For active rank queries and cleanup
    public long expiresAt;          // Unix timestamp when rank expires
    
    @Index(name = "idx_granted_at", order = IndexOrder.DESC)  // For history ordering
    public long grantedAt;          // Unix timestamp when rank was granted
    
    public String grantedBy;        // Who granted the rank (player name or system)
    public boolean autoRenew;       // Whether to auto-renew this rank

    /**
     * Check if this monthly rank is currently active (not expired).
     */
    public boolean isActive() {
        return System.currentTimeMillis() < expiresAt;
    }

    /**
     * Check if this monthly rank has expired.
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    /**
     * Get the remaining time in milliseconds before expiration.
     */
    public long getRemainingTime() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    /**
     * Get the remaining days before expiration.
     */
    public long getRemainingDays() {
        return getRemainingTime() / (1000 * 60 * 60 * 24);
    }
}