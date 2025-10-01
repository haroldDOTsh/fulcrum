package sh.harold.fulcrum.lifecycle;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class FeatureManager {
    private static final List<PluginFeature> features = new ArrayList<>();
    private static DependencyContainer container;
    private static JavaPlugin plugin;

    private FeatureManager() {
        // Prevent instantiation
    }

    public static void register(PluginFeature feature) {
        features.add(feature);
    }

    /**
     * Initialize all features with dependency injection support.
     * Features are initialized in priority order, with dependency checking.
     */
    public static void initializeAll(JavaPlugin plugin, DependencyContainer container) {
        FeatureManager.plugin = plugin;
        FeatureManager.container = container;

        // Sort features by priority
        List<PluginFeature> sortedFeatures = new ArrayList<>(features);
        sortedFeatures.sort(Comparator.comparingInt(PluginFeature::getPriority));

        // Validate dependencies before initialization
        for (PluginFeature feature : sortedFeatures) {
            validateDependencies(feature);
        }

        // Initialize features in priority order
        plugin.getLogger().info("=== FEATURE INITIALIZATION ORDER ===");
        for (PluginFeature feature : sortedFeatures) {
            plugin.getLogger().info("Initializing: " + feature.getClass().getSimpleName()
                    + " (priority: " + feature.getPriority() + ")");
            try {
                feature.initialize(plugin, container);
                plugin.getLogger().info("Successfully initialized: " + feature.getClass().getSimpleName());
            } catch (Exception e) {
                plugin.getLogger().severe("FAILED to initialize: " + feature.getClass().getSimpleName()
                        + " - " + e.getMessage());
                e.printStackTrace();
            }
        }
        plugin.getLogger().info("=== END FEATURE INITIALIZATION ===");
    }

    /**
     * Legacy initialization method without dependency injection.
     *
     * @deprecated Use initializeAll(JavaPlugin, DependencyContainer) instead
     */
    @Deprecated
    public static void initializeAll(JavaPlugin plugin) {
        initializeAll(plugin, new DependencyContainer());
    }

    /**
     * Validate that all dependencies for a feature are available.
     */
    private static void validateDependencies(PluginFeature feature) {
        Class<?>[] dependencies = feature.getDependencies();
        for (Class<?> dependency : dependencies) {
            if (!container.isAvailable(dependency)) {
                plugin.getLogger().warning("Feature " + feature.getClass().getSimpleName()
                        + " has unmet dependency: " + dependency.getSimpleName());
            }
        }
    }

    public static void shutdownAll() {
        // Shutdown in reverse order
        List<PluginFeature> reverseFeatures = new ArrayList<>(features);
        Collections.reverse(reverseFeatures);

        for (PluginFeature feature : reverseFeatures) {
            try {
                feature.shutdown();
            } catch (Exception e) {
                if (plugin != null) {
                    plugin.getLogger().severe("Error shutting down feature: "
                            + feature.getClass().getSimpleName());
                }
                e.printStackTrace();
            }
        }

        clear();
    }

    /**
     * Get a feature by its class type.
     *
     * @param featureClass The feature class
     * @param <T>          The feature type
     * @return The feature instance, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T extends PluginFeature> T getFeature(Class<T> featureClass) {
        for (PluginFeature feature : features) {
            if (featureClass.isInstance(feature)) {
                return (T) feature;
            }
        }
        return null;
    }

    /**
     * Clear all registered features and cached state.
     * Used mainly for testing and reload-safe teardown.
     */
    public static void clear() {
        features.clear();
        if (container != null) {
            container.clear();
        }
        container = null;
        plugin = null;
    }
}
