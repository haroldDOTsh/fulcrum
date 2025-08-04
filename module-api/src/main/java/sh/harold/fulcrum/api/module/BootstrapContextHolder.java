package sh.harold.fulcrum.api.module;

/**
 * Thread-local storage for bootstrap context during module initialization.
 * This allows modules to query their enablement status during the bootstrap phase
 * without requiring parameters or complex context detection.
 * 
 * @since 1.3.0
 */
public final class BootstrapContextHolder {
    private static final ThreadLocal<String> currentModuleId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> inBootstrapPhase = ThreadLocal.withInitial(() -> false);
    
    private BootstrapContextHolder() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Sets the bootstrap context for the current thread.
     * This should be called at the beginning of a module's bootstrap method.
     * 
     * @param moduleId the module ID from the @ModuleID annotation
     */
    public static void setContext(String moduleId) {
        currentModuleId.set(moduleId);
        inBootstrapPhase.set(true);
    }
    
    /**
     * Gets the current module ID from the thread-local context.
     * 
     * @return the current module ID, or null if not in bootstrap context
     */
    public static String getCurrentModuleId() {
        return currentModuleId.get();
    }
    
    /**
     * Checks if the current thread is in the bootstrap phase.
     * 
     * @return true if currently in bootstrap phase
     */
    public static boolean isInBootstrapPhase() {
        return inBootstrapPhase.get();
    }
    
    /**
     * Clears the bootstrap context for the current thread.
     * This should be called at the end of a module's bootstrap method (in a finally block).
     */
    public static void clearContext() {
        currentModuleId.remove();
        inBootstrapPhase.remove();
    }
}