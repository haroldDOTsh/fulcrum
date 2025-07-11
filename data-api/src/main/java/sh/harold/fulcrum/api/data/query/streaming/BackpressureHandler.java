package sh.harold.fulcrum.api.data.query.streaming;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Handles backpressure for streaming operations to prevent memory overflow.
 * 
 * <p>This class implements various strategies to handle situations where
 * data is being produced faster than it can be consumed. It monitors
 * memory usage and applies appropriate backpressure techniques.</p>
 * 
 * <p>Strategies include:</p>
 * <ul>
 *   <li>Adaptive delays based on buffer saturation</li>
 *   <li>Memory pressure monitoring</li>
 *   <li>Automatic garbage collection triggers</li>
 *   <li>Producer throttling</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class BackpressureHandler {
    
    private static final Logger LOGGER = Logger.getLogger(BackpressureHandler.class.getName());
    
    /**
     * Backpressure strategies available.
     */
    public enum Strategy {
        /**
         * Apply exponential backoff delays.
         */
        EXPONENTIAL_BACKOFF,
        
        /**
         * Drop oldest items when buffer is full.
         */
        DROP_OLDEST,
        
        /**
         * Drop newest items when buffer is full.
         */
        DROP_NEWEST,
        
        /**
         * Block producers until buffer has space.
         */
        BLOCK,
        
        /**
         * Adaptively adjust based on consumption rate.
         */
        ADAPTIVE
    }
    
    private final Strategy strategy;
    private final long maxMemoryThreshold;
    private final AtomicLong backoffDelayMs;
    private final AtomicLong lastGcTime;
    private final Runtime runtime;
    
    // Metrics for adaptive strategy
    private final AtomicLong totalProduced;
    private final AtomicLong totalConsumed;
    private final AtomicLong lastCheckTime;
    
    /**
     * Creates a BackpressureHandler with default ADAPTIVE strategy.
     */
    public BackpressureHandler() {
        this(Strategy.ADAPTIVE, calculateDefaultMemoryThreshold());
    }
    
    /**
     * Creates a BackpressureHandler with specified strategy.
     * 
     * @param strategy The backpressure strategy to use
     */
    public BackpressureHandler(Strategy strategy) {
        this(strategy, calculateDefaultMemoryThreshold());
    }
    
    /**
     * Creates a BackpressureHandler with specified strategy and memory threshold.
     * 
     * @param strategy The backpressure strategy to use
     * @param maxMemoryThreshold Maximum memory usage before triggering backpressure (in bytes)
     */
    public BackpressureHandler(Strategy strategy, long maxMemoryThreshold) {
        this.strategy = strategy;
        this.maxMemoryThreshold = maxMemoryThreshold;
        this.runtime = Runtime.getRuntime();
        this.backoffDelayMs = new AtomicLong(10); // Start with 10ms
        this.lastGcTime = new AtomicLong(System.currentTimeMillis());
        
        // Metrics initialization
        this.totalProduced = new AtomicLong(0);
        this.totalConsumed = new AtomicLong(0);
        this.lastCheckTime = new AtomicLong(System.currentTimeMillis());
    }
    
    /**
     * Calculates a reasonable default memory threshold (75% of max heap).
     * 
     * @return Memory threshold in bytes
     */
    private static long calculateDefaultMemoryThreshold() {
        return (long) (Runtime.getRuntime().maxMemory() * 0.75);
    }
    
    /**
     * Handles backpressure based on current buffer state.
     * 
     * @param currentSize Current number of items in buffer
     * @param remainingCapacity Remaining capacity in buffer
     */
    public void handleBackpressure(int currentSize, int remainingCapacity) {
        // Check memory pressure
        checkMemoryPressure();
        
        // Calculate buffer saturation percentage
        double saturation = (double) currentSize / (currentSize + remainingCapacity);
        
        switch (strategy) {
            case EXPONENTIAL_BACKOFF:
                handleExponentialBackoff(saturation);
                break;
                
            case ADAPTIVE:
                handleAdaptiveBackpressure(saturation);
                break;
                
            case BLOCK:
                // BLOCK strategy is handled by the caller (blocking queue)
                break;
                
            case DROP_OLDEST:
            case DROP_NEWEST:
                // These strategies require buffer access, handled elsewhere
                LOGGER.log(Level.WARNING, 
                    "DROP strategies not implemented in handler, should be handled by buffer");
                break;
        }
    }
    
    /**
     * Handles exponential backoff based on buffer saturation.
     * 
     * @param saturation Buffer saturation level (0.0 to 1.0)
     */
    private void handleExponentialBackoff(double saturation) {
        if (saturation > 0.8) {
            // High saturation, increase backoff
            long delay = backoffDelayMs.get();
            if (delay < 1000) { // Cap at 1 second
                backoffDelayMs.compareAndSet(delay, Math.min(delay * 2, 1000));
            }
            
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (saturation < 0.5) {
            // Low saturation, decrease backoff
            long delay = backoffDelayMs.get();
            if (delay > 10) {
                backoffDelayMs.compareAndSet(delay, Math.max(delay / 2, 10));
            }
        }
    }
    
    /**
     * Handles adaptive backpressure based on production/consumption rates.
     * 
     * @param saturation Buffer saturation level (0.0 to 1.0)
     */
    private void handleAdaptiveBackpressure(double saturation) {
        long now = System.currentTimeMillis();
        long timeDiff = now - lastCheckTime.get();
        
        if (timeDiff > 1000) { // Check every second
            lastCheckTime.set(now);
            
            // Calculate rates
            double productionRate = totalProduced.get() / (timeDiff / 1000.0);
            double consumptionRate = totalConsumed.get() / (timeDiff / 1000.0);
            
            if (productionRate > consumptionRate * 1.5 && saturation > 0.7) {
                // Production significantly faster than consumption
                long delay = Math.min((long) ((productionRate / consumptionRate) * 10), 500);
                
                LOGGER.log(Level.FINE, 
                    String.format("Adaptive backpressure: prod=%.2f/s, cons=%.2f/s, delay=%dms",
                        productionRate, consumptionRate, delay));
                
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Reset counters
            totalProduced.set(0);
            totalConsumed.set(0);
        }
    }
    
    /**
     * Checks memory pressure and triggers GC if necessary.
     */
    private void checkMemoryPressure() {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        
        if (usedMemory > maxMemoryThreshold) {
            long now = System.currentTimeMillis();
            long lastGc = lastGcTime.get();
            
            // Trigger GC at most once every 5 seconds
            if (now - lastGc > 5000 && lastGcTime.compareAndSet(lastGc, now)) {
                LOGGER.log(Level.INFO, 
                    String.format("Memory pressure detected: %d MB used, triggering GC",
                        usedMemory / (1024 * 1024)));
                
                System.gc();
                
                // Additional delay to let GC complete
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
    
    /**
     * Records a production event for metrics.
     * 
     * @param count Number of items produced
     */
    public void recordProduction(long count) {
        totalProduced.addAndGet(count);
    }
    
    /**
     * Records a consumption event for metrics.
     * 
     * @param count Number of items consumed
     */
    public void recordConsumption(long count) {
        totalConsumed.addAndGet(count);
    }
    
    /**
     * Gets the current backpressure strategy.
     * 
     * @return The current strategy
     */
    public Strategy getStrategy() {
        return strategy;
    }
    
    /**
     * Gets the current memory usage percentage.
     * 
     * @return Memory usage as a percentage (0.0 to 1.0)
     */
    public double getMemoryUsage() {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }
    
    /**
     * Checks if the system is under memory pressure.
     * 
     * @return true if memory usage exceeds threshold
     */
    public boolean isUnderMemoryPressure() {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return usedMemory > maxMemoryThreshold;
    }
    
    /**
     * Resets all metrics and counters.
     */
    public void reset() {
        backoffDelayMs.set(10);
        totalProduced.set(0);
        totalConsumed.set(0);
        lastCheckTime.set(System.currentTimeMillis());
    }
}