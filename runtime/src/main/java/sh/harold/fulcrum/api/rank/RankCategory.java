package sh.harold.fulcrum.api.rank;

/**
 * Categories for different types of ranks in the system.
 */
public enum RankCategory {
    /**
     * Standard player ranks (DEFAULT, VIP, MVP, etc.)
     */
    PLAYER,
    
    /**
     * Subscription-based ranks (MVP_PLUS, MVP_PLUS_PLUS)
     */
    SUBSCRIPTION,
    
    /**
     * Staff ranks (HELPER, MODERATOR, ADMIN)
     */
    STAFF,
    
    /**
     * Special ranks (YOUTUBER, etc.)
     */
    SPECIAL
}