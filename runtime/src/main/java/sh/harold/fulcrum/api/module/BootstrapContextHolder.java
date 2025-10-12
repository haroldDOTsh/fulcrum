package sh.harold.fulcrum.api.module;

/**
 * Holds bootstrap context information for module enablement checks.
 * This allows modules to identify themselves during the bootstrap phase
 * so FulcrumEnvironment can determine if they should be enabled.
 */
public class BootstrapContextHolder {
    private static final ThreadLocal<BootstrapContext> context = new ThreadLocal<>();

    /**
     * Set the context for the current module
     *
     * @param moduleId The ID of the module being loaded
     */
    public static void setContext(String moduleId) {
        context.set(new BootstrapContext(moduleId));
    }

    /**
     * Clear the context after module loading
     */
    public static void clearContext() {
        context.remove();
    }

    /**
     * Get the current module ID from context
     *
     * @return The module ID or null if not in bootstrap phase
     */
    public static String getCurrentModuleId() {
        BootstrapContext ctx = context.get();
        return ctx != null ? ctx.moduleId : null;
    }

    /**
     * Check if we're in the bootstrap phase
     *
     * @return true if in bootstrap phase
     */
    public static boolean isInBootstrapPhase() {
        BootstrapContext ctx = context.get();
        return ctx != null && ctx.inBootstrapPhase;
    }

    /**
     * Context information for the bootstrap phase
     */
    private static class BootstrapContext {
        private final String moduleId;
        private final boolean inBootstrapPhase;

        BootstrapContext(String moduleId) {
            this.moduleId = moduleId;
            this.inBootstrapPhase = true;
        }
    }
}