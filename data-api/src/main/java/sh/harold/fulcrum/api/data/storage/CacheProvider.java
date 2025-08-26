package sh.harold.fulcrum.api.data.storage;

/**
 * Interface for cache providers to optimize data access.
 * Implementations can use different caching strategies (Redis, Caffeine, etc.)
 */
public interface CacheProvider {
    
    /**
     * Get a value from cache.
     * 
     * @param key The cache key
     * @return The cached value, or null if not found
     */
    Object get(String key);
    
    /**
     * Put a value into cache.
     * 
     * @param key The cache key
     * @param value The value to cache
     */
    void put(String key, Object value);
    
    /**
     * Put a value into cache with TTL.
     * 
     * @param key The cache key
     * @param value The value to cache
     * @param ttlSeconds Time to live in seconds
     */
    void put(String key, Object value, int ttlSeconds);
    
    /**
     * Remove a value from cache.
     * 
     * @param key The cache key
     */
    void remove(String key);
    
    /**
     * Clear all cached values.
     */
    void clear();
    
    /**
     * Check if a key exists in cache.
     * 
     * @param key The cache key
     * @return true if the key exists
     */
    boolean contains(String key);
}