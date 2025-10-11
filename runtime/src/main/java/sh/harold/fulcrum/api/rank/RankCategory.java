package sh.harold.fulcrum.api.rank;

/**
 * Categories for different types of ranks in the system.
 */
public enum RankCategory {
    /**
     * Standard player ranks (DEFAULT, DONATOR_1-3, etc.)
     */
    PLAYER,
    
    /**
     * Subscription-based ranks (e.g., DONATOR_4)
     */
    SUBSCRIPTION,
    
    /**
     * Staff ranks (HELPER, STAFF)
     */
    STAFF,
    
    /**
     * Special ranks (YOUTUBER, etc.)
     */
    SPECIAL
}
