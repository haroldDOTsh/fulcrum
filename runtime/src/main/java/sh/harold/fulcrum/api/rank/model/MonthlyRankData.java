package sh.harold.fulcrum.api.rank.model;

import sh.harold.fulcrum.api.data.annotation.*;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;

import java.util.UUID;

/**
 * Data schema for time-limited monthly ranks.
 * Stores expiration information and metadata about monthly rank grants.
 *
 * Performance indexes are optimized for the most common query patterns:
 * - Rank filtering queries (rank)
 * - Active rank lookups and expiration checks (expiresAt)
 * - Active rank filtering (rank + expiresAt)
 * - Historical queries (grantedAt)
 */
@Table("monthly_ranks")
@SchemaVersion(1)
@Indexes({
    @CompositeIndex(
        name = "idx_rank_expires_at",
        fields = {"rank", "expiresAt"},
        orders = {IndexOrder.ASC, IndexOrder.DESC}
    )
})
public class MonthlyRankData {

    @Column(primary = true, generation = PrimaryKeyGeneration.PLAYER_UUID)
    public UUID uuid;

    @Index(name = "idx_rank")  // For rank filtering queries
    public MonthlyPackageRank rank;
    
    @Index(name = "idx_expires_at", order = IndexOrder.DESC)  // For expiration checks and active rank queries
    public long expiresAt;          // Unix timestamp when rank expires
    
    @Index(name = "idx_granted_at", order = IndexOrder.DESC)  // For historical queries
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