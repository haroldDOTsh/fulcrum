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
     * Get the initialization priority for this feature.
     * Lower values initialize first. Default is 100.
     * Core features should use 0-50, normal features 50-100,
     * and dependent features 100+.
     */
    default int getPriority() {
        return 100;
    }

}
