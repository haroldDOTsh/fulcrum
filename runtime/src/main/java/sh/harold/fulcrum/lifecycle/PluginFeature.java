package sh.harold.fulcrum.lifecycle;

import org.bukkit.plugin.java.JavaPlugin;

public interface PluginFeature {
    /**
     * Initialize the feature with plugin and dependency container.
     * Features should retrieve dependencies from the container rather than
     * directly from the plugin to avoid initialization order issues.
     *
     * @param plugin    The plugin instance
     * @param container The dependency container (optional, for backward compatibility)
     */
    default void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Default implementation for backward compatibility
        initialize(plugin);
    }

    /**
     * Legacy initialization method.
     *
     * @deprecated Use initialize(JavaPlugin, DependencyContainer) instead
     */
    @Deprecated
    default void initialize(JavaPlugin plugin) {
        // Default empty implementation for new features
    }

    /**
     * Shutdown the feature and clean up resources.
     */
    default void shutdown() {
        // This method is optional
    }

    /**
     * Whether the feature needs an additional pass after the server finishes loading worlds.
     * Only fundamentals that depend on POSTWORLD plugins should override this.
     */
    default boolean requiresPostWorldPass() {
        return false;
    }

    /**
     * Optional hook invoked after Paper fires {@link org.bukkit.event.server.ServerLoadEvent}
     * with {@link org.bukkit.event.server.ServerLoadEvent.LoadType#STARTUP}.
     * Runs on the main thread, after all dependencies declared in paper-plugin.yml have loaded.
     */
    default void postWorldInitialize(JavaPlugin plugin, DependencyContainer container) {
        // Default no-op
    }

    /**
     * Get the initialization priority for this feature.
     * Lower values initialize first. Default is 100.
     * Core features should use 0-50, normal features 50-100,
     * and dependent features 100+.
     */
    default int getPriority() {
        return 100;
    }

}
