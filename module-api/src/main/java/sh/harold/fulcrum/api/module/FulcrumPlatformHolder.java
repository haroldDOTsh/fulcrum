package sh.harold.fulcrum.api.module;

/**
 * Static holder for FulcrumPlatform instance to provide access to external modules.
 * This follows the same pattern as FulcrumEnvironment for consistency.
 */
public final class FulcrumPlatformHolder {
    private static FulcrumPlatform instance;
    
    private FulcrumPlatformHolder() {
        // Private constructor to prevent instantiation
    }
    
    /**
     * Initialize the FulcrumPlatform instance.
     * This should only be called once during plugin initialization.
     *
     * @param platform The FulcrumPlatform instance to store
     * @throws IllegalStateException if already initialized
     */
    public static void initialize(FulcrumPlatform platform) {
        if (instance != null) {
            throw new IllegalStateException("FulcrumPlatform has already been initialized");
        }
        if (platform == null) {
            throw new IllegalArgumentException("FulcrumPlatform cannot be null");
        }
        instance = platform;
    }
    
    /**
     * Get the FulcrumPlatform instance.
     *
     * @return The FulcrumPlatform instance
     * @throws IllegalStateException if not yet initialized
     */
    public static FulcrumPlatform getPlatform() {
        if (instance == null) {
            throw new IllegalStateException("FulcrumPlatform has not been initialized. " +
                "This usually means you're trying to access it before the Fulcrum plugin has loaded.");
        }
        return instance;
    }
    
    /**
     * Check if the FulcrumPlatform has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return instance != null;
    }
    
    /**
     * Clear the stored instance. This should only be used for testing purposes.
     */
    static void reset() {
        instance = null;
    }
}