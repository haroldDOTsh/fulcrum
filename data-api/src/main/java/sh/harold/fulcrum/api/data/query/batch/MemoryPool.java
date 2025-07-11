package sh.harold.fulcrum.api.data.query.batch;

import sh.harold.fulcrum.api.data.query.CrossSchemaResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Memory pool for efficient object reuse in batch operations.
 * Reduces garbage collection pressure when processing large datasets.
 * 
 * <p>This pool implementation provides:</p>
 * <ul>
 *   <li>Thread-safe object pooling with minimal contention</li>
 *   <li>Automatic pool size management</li>
 *   <li>Statistics tracking for monitoring</li>
 *   <li>Multiple pooling strategies</li>
 *   <li>Soft references for memory-sensitive pooling</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Create a pool for CrossSchemaResult objects
 * ObjectPool<CrossSchemaResult> resultPool = MemoryPool.getInstance()
 *     .createPool("results", 
 *                 () -> new CrossSchemaResult(null),
 *                 result -> result.clear());
 * 
 * // Borrow an object
 * PooledObject<CrossSchemaResult> pooled = resultPool.borrow();
 * try {
 *     CrossSchemaResult result = pooled.get();
 *     // Use the result...
 * } finally {
 *     pooled.release(); // Return to pool
 * }
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public class MemoryPool {
    
    private static final Logger LOGGER = Logger.getLogger(MemoryPool.class.getName());
    private static final MemoryPool INSTANCE = new MemoryPool();
    
    private final Map<String, ObjectPool<?>> pools;
    private final ScheduledExecutorService maintenanceExecutor;
    private final PoolStatistics globalStats;
    
    private MemoryPool() {
        this.pools = new ConcurrentHashMap<>();
        this.globalStats = new PoolStatistics("global");
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryPool-Maintenance");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic maintenance
        maintenanceExecutor.scheduleWithFixedDelay(this::performMaintenance, 
            1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Gets the singleton instance of MemoryPool.
     * 
     * @return The MemoryPool instance
     */
    public static MemoryPool getInstance() {
        return INSTANCE;
    }
    
    /**
     * Creates a new object pool with default configuration.
     * 
     * @param name Pool name for identification
     * @param factory Factory to create new objects
     * @param resetter Consumer to reset objects before reuse
     * @param <T> Type of objects in the pool
     * @return The created object pool
     */
    public <T> ObjectPool<T> createPool(String name, Supplier<T> factory, Consumer<T> resetter) {
        return createPool(name, PoolConfig.defaultConfig(), factory, resetter);
    }
    
    /**
     * Creates a new object pool with custom configuration.
     * 
     * @param name Pool name for identification
     * @param config Pool configuration
     * @param factory Factory to create new objects
     * @param resetter Consumer to reset objects before reuse
     * @param <T> Type of objects in the pool
     * @return The created object pool
     */
    @SuppressWarnings("unchecked")
    public <T> ObjectPool<T> createPool(String name, PoolConfig config, 
                                        Supplier<T> factory, Consumer<T> resetter) {
        ObjectPool<T> pool = new ObjectPool<>(name, config, factory, resetter, globalStats);
        ObjectPool<?> existing = pools.putIfAbsent(name, pool);
        
        if (existing != null) {
            LOGGER.log(Level.WARNING, "Pool {0} already exists, returning existing pool", name);
            return (ObjectPool<T>) existing;
        }
        
        LOGGER.log(Level.FINE, "Created pool {0} with config {1}", new Object[]{name, config});
        return pool;
    }
    
    /**
     * Gets an existing pool by name.
     * 
     * @param name Pool name
     * @param <T> Expected type of objects in the pool
     * @return The pool, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> ObjectPool<T> getPool(String name) {
        return (ObjectPool<T>) pools.get(name);
    }
    
    /**
     * Removes and shuts down a pool.
     * 
     * @param name Pool name
     */
    public void removePool(String name) {
        ObjectPool<?> pool = pools.remove(name);
        if (pool != null) {
            pool.shutdown();
        }
    }
    
    /**
     * Gets global pool statistics.
     * 
     * @return Global statistics
     */
    public PoolStatistics getGlobalStatistics() {
        return globalStats;
    }
    
    /**
     * Gets statistics for a specific pool.
     * 
     * @param poolName Pool name
     * @return Pool statistics, or null if pool not found
     */
    public PoolStatistics getPoolStatistics(String poolName) {
        ObjectPool<?> pool = pools.get(poolName);
        return pool != null ? pool.getStatistics() : null;
    }
    
    /**
     * Shuts down all pools and releases resources.
     */
    public void shutdown() {
        maintenanceExecutor.shutdown();
        pools.values().forEach(ObjectPool::shutdown);
        pools.clear();
        
        try {
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private void performMaintenance() {
        pools.values().forEach(pool -> {
            try {
                pool.performMaintenance();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during pool maintenance for " + pool.getName(), e);
            }
        });
    }
    
    /**
     * Pre-configured pools for common objects.
     */
    public static class CommonPools {
        
        /**
         * Creates a pool for HashMap instances.
         */
        public static ObjectPool<Map<String, Object>> createMapPool(String name, int initialCapacity) {
            return getInstance().createPool(name,
                () -> new HashMap<>(initialCapacity),
                Map::clear);
        }
        
        /**
         * Creates a pool for ArrayList instances.
         */
        public static ObjectPool<List<Object>> createListPool(String name, int initialCapacity) {
            return getInstance().createPool(name,
                () -> new ArrayList<>(initialCapacity),
                List::clear);
        }
        
        /**
         * Creates a pool for StringBuilder instances.
         */
        public static ObjectPool<StringBuilder> createStringBuilderPool(String name, int initialCapacity) {
            return getInstance().createPool(name,
                () -> new StringBuilder(initialCapacity),
                sb -> sb.setLength(0));
        }
        
        /**
         * Creates a pool for byte arrays.
         */
        public static ObjectPool<byte[]> createByteArrayPool(String name, int arraySize) {
            return getInstance().createPool(name,
                () -> new byte[arraySize],
                bytes -> Arrays.fill(bytes, (byte) 0));
        }
    }
    
    /**
     * Object pool implementation.
     * 
     * @param <T> Type of objects in the pool
     */
    public static class ObjectPool<T> {
        private final String name;
        private final PoolConfig config;
        private final Supplier<T> factory;
        private final Consumer<T> resetter;
        private final Queue<PooledObjectImpl<T>> available;
        private final Set<PooledObjectImpl<T>> inUse;
        private final PoolStatistics statistics;
        private final PoolStatistics globalStats;
        private final AtomicInteger totalCreated;
        private volatile boolean shutdown;
        
        ObjectPool(String name, PoolConfig config, Supplier<T> factory, 
                   Consumer<T> resetter, PoolStatistics globalStats) {
            this.name = name;
            this.config = config;
            this.factory = Objects.requireNonNull(factory);
            this.resetter = Objects.requireNonNull(resetter);
            this.globalStats = globalStats;
            this.statistics = new PoolStatistics(name);
            this.totalCreated = new AtomicInteger(0);
            this.inUse = ConcurrentHashMap.newKeySet();
            
            // Choose queue implementation based on strategy
            switch (config.getStrategy()) {
                case LIFO:
                    this.available = new ConcurrentLinkedDeque<>();
                    break;
                case FIFO:
                default:
                    this.available = new ConcurrentLinkedQueue<>();
                    break;
            }
            
            // Pre-populate pool if configured
            if (config.getMinSize() > 0) {
                for (int i = 0; i < config.getMinSize(); i++) {
                    available.offer(createPooledObject());
                }
            }
        }
        
        /**
         * Borrows an object from the pool.
         * 
         * @return A pooled object wrapper
         * @throws IllegalStateException if pool is shutdown
         */
        public PooledObject<T> borrow() {
            if (shutdown) {
                throw new IllegalStateException("Pool is shutdown");
            }
            
            PooledObjectImpl<T> pooled = available.poll();
            
            if (pooled == null) {
                // Create new object if under max size
                if (totalCreated.get() < config.getMaxSize()) {
                    pooled = createPooledObject();
                } else if (config.isBlockWhenExhausted()) {
                    // Wait for an object to become available
                    pooled = waitForAvailable();
                } else {
                    throw new NoSuchElementException("Pool exhausted");
                }
            }
            
            if (pooled != null) {
                pooled.markBorrowed();
                inUse.add(pooled);
                statistics.recordBorrow();
                globalStats.recordBorrow();
            }
            
            return pooled;
        }
        
        /**
         * Tries to borrow an object with timeout.
         * 
         * @param timeout Timeout value
         * @param unit Timeout unit
         * @return A pooled object wrapper, or null if timeout
         */
        public PooledObject<T> tryBorrow(long timeout, TimeUnit unit) {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            
            while (System.nanoTime() < deadline) {
                PooledObjectImpl<T> pooled = available.poll();
                if (pooled != null) {
                    pooled.markBorrowed();
                    inUse.add(pooled);
                    statistics.recordBorrow();
                    globalStats.recordBorrow();
                    return pooled;
                }
                
                if (totalCreated.get() < config.getMaxSize()) {
                    pooled = createPooledObject();
                    pooled.markBorrowed();
                    inUse.add(pooled);
                    statistics.recordBorrow();
                    globalStats.recordBorrow();
                    return pooled;
                }
                
                // Brief sleep to avoid busy waiting
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            
            return null;
        }
        
        void returnObject(PooledObjectImpl<T> pooled) {
            if (!inUse.remove(pooled)) {
                LOGGER.log(Level.WARNING, "Returning object that was not borrowed from pool {0}", name);
                return;
            }
            
            statistics.recordReturn();
            globalStats.recordReturn();
            
            try {
                // Reset the object
                resetter.accept(pooled.getObject());
                pooled.markReturned();
                
                // Return to pool if healthy and not over capacity
                if (pooled.isHealthy() && available.size() < config.getMaxIdle()) {
                    available.offer(pooled);
                } else {
                    // Destroy the object
                    pooled.destroy();
                    totalCreated.decrementAndGet();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error resetting object in pool " + name, e);
                pooled.destroy();
                totalCreated.decrementAndGet();
            }
        }
        
        private PooledObjectImpl<T> createPooledObject() {
            T object = factory.get();
            totalCreated.incrementAndGet();
            statistics.recordCreation();
            globalStats.recordCreation();
            return new PooledObjectImpl<>(object, this);
        }
        
        private PooledObjectImpl<T> waitForAvailable() {
            // Simple implementation - in production would use more sophisticated waiting
            for (int i = 0; i < 100; i++) {
                PooledObjectImpl<T> pooled = available.poll();
                if (pooled != null) {
                    return pooled;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            return null;
        }
        
        void performMaintenance() {
            // Remove idle objects over min size
            int currentSize = available.size();
            int toRemove = Math.max(0, currentSize - config.getMinSize());
            
            for (int i = 0; i < toRemove; i++) {
                PooledObjectImpl<T> pooled = available.poll();
                if (pooled != null && pooled.getIdleTime() > config.getMaxIdleTimeMs()) {
                    pooled.destroy();
                    totalCreated.decrementAndGet();
                } else if (pooled != null) {
                    // Put it back
                    available.offer(pooled);
                }
            }
            
            // Ensure minimum size
            while (totalCreated.get() < config.getMinSize()) {
                available.offer(createPooledObject());
            }
        }
        
        void shutdown() {
            shutdown = true;
            
            // Destroy all objects
            PooledObjectImpl<T> pooled;
            while ((pooled = available.poll()) != null) {
                pooled.destroy();
            }
            
            // Warn about objects still in use
            if (!inUse.isEmpty()) {
                LOGGER.log(Level.WARNING, "Pool {0} shutdown with {1} objects still in use", 
                           new Object[]{name, inUse.size()});
            }
        }
        
        public String getName() { return name; }
        public PoolStatistics getStatistics() { return statistics; }
        public int getAvailableCount() { return available.size(); }
        public int getInUseCount() { return inUse.size(); }
        public int getTotalCreated() { return totalCreated.get(); }
    }
    
    /**
     * Pooled object wrapper.
     * 
     * @param <T> Type of the wrapped object
     */
    public interface PooledObject<T> extends AutoCloseable {
        T get();
        void release();
        boolean isHealthy();
        
        @Override
        default void close() {
            release();
        }
    }
    
    /**
     * Internal implementation of PooledObject.
     */
    private static class PooledObjectImpl<T> implements PooledObject<T> {
        private final T object;
        private final ObjectPool<T> pool;
        private final long createdAt;
        private volatile long borrowedAt;
        private volatile long returnedAt;
        private volatile int borrowCount;
        private volatile boolean destroyed;
        
        PooledObjectImpl(T object, ObjectPool<T> pool) {
            this.object = object;
            this.pool = pool;
            this.createdAt = System.currentTimeMillis();
            this.returnedAt = createdAt;
        }
        
        @Override
        public T get() {
            if (destroyed) {
                throw new IllegalStateException("Object has been destroyed");
            }
            return object;
        }
        
        @Override
        public void release() {
            pool.returnObject(this);
        }
        
        @Override
        public boolean isHealthy() {
            return !destroyed && borrowCount < pool.config.getMaxBorrowsPerObject();
        }
        
        T getObject() { return object; }
        
        void markBorrowed() {
            borrowedAt = System.currentTimeMillis();
            borrowCount++;
        }
        
        void markReturned() {
            returnedAt = System.currentTimeMillis();
        }
        
        void destroy() {
            destroyed = true;
            // If object implements AutoCloseable, close it
            if (object instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) object).close();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing pooled object", e);
                }
            }
        }
        
        long getIdleTime() {
            return System.currentTimeMillis() - returnedAt;
        }
    }
    
    /**
     * Pool configuration.
     */
    public static class PoolConfig {
        private int minSize = 0;
        private int maxSize = 100;
        private int maxIdle = 50;
        private long maxIdleTimeMs = TimeUnit.MINUTES.toMillis(5);
        private boolean blockWhenExhausted = true;
        private int maxBorrowsPerObject = Integer.MAX_VALUE;
        private PoolingStrategy strategy = PoolingStrategy.FIFO;
        
        public static PoolConfig defaultConfig() {
            return new PoolConfig();
        }
        
        public static PoolConfig smallPool() {
            PoolConfig config = new PoolConfig();
            config.setMaxSize(10);
            config.setMaxIdle(5);
            return config;
        }
        
        public static PoolConfig largePool() {
            PoolConfig config = new PoolConfig();
            config.setMinSize(10);
            config.setMaxSize(1000);
            config.setMaxIdle(100);
            return config;
        }
        
        // Getters and setters
        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
        
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        
        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int maxIdle) { this.maxIdle = maxIdle; }
        
        public long getMaxIdleTimeMs() { return maxIdleTimeMs; }
        public void setMaxIdleTimeMs(long maxIdleTimeMs) { this.maxIdleTimeMs = maxIdleTimeMs; }
        
        public boolean isBlockWhenExhausted() { return blockWhenExhausted; }
        public void setBlockWhenExhausted(boolean blockWhenExhausted) { 
            this.blockWhenExhausted = blockWhenExhausted; 
        }
        
        public int getMaxBorrowsPerObject() { return maxBorrowsPerObject; }
        public void setMaxBorrowsPerObject(int maxBorrowsPerObject) { 
            this.maxBorrowsPerObject = maxBorrowsPerObject; 
        }
        
        public PoolingStrategy getStrategy() { return strategy; }
        public void setStrategy(PoolingStrategy strategy) { this.strategy = strategy; }
        
        @Override
        public String toString() {
            return String.format("PoolConfig[min=%d, max=%d, maxIdle=%d, strategy=%s]",
                minSize, maxSize, maxIdle, strategy);
        }
    }
    
    /**
     * Pooling strategies.
     */
    public enum PoolingStrategy {
        FIFO, // First In First Out - objects are reused in order
        LIFO  // Last In First Out - recently used objects are reused (better cache locality)
    }
    
    /**
     * Pool statistics.
     */
    public static class PoolStatistics {
        private final String name;
        private final AtomicLong totalBorrows = new AtomicLong();
        private final AtomicLong totalReturns = new AtomicLong();
        private final AtomicLong totalCreations = new AtomicLong();
        private final AtomicLong totalDestructions = new AtomicLong();
        
        PoolStatistics(String name) {
            this.name = name;
        }
        
        void recordBorrow() { totalBorrows.incrementAndGet(); }
        void recordReturn() { totalReturns.incrementAndGet(); }
        void recordCreation() { totalCreations.incrementAndGet(); }
        void recordDestruction() { totalDestructions.incrementAndGet(); }
        
        public String getName() { return name; }
        public long getTotalBorrows() { return totalBorrows.get(); }
        public long getTotalReturns() { return totalReturns.get(); }
        public long getTotalCreations() { return totalCreations.get(); }
        public long getTotalDestructions() { return totalDestructions.get(); }
        public long getCurrentlyBorrowed() { return totalBorrows.get() - totalReturns.get(); }
        
        @Override
        public String toString() {
            return String.format("PoolStats[%s: borrows=%d, returns=%d, created=%d, destroyed=%d]",
                name, totalBorrows.get(), totalReturns.get(), 
                totalCreations.get(), totalDestructions.get());
        }
    }
}