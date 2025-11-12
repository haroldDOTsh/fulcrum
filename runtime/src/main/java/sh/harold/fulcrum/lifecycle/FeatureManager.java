package sh.harold.fulcrum.lifecycle;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Features are initialized in priority order.
     */
    public static void initializeAll(JavaPlugin plugin, DependencyContainer container) {
        FeatureManager.plugin = plugin;
        FeatureManager.container = container;

        // Sort features by priority
        List<PluginFeature> sortedFeatures = new ArrayList<>(features);
        sortedFeatures.sort(Comparator.comparingInt(PluginFeature::getPriority));
        List<PluginFeature> deferredFeatures = new ArrayList<>();

        // Initialize features in priority order
        plugin.getLogger().info("=== FEATURE INITIALIZATION ORDER ===");
        for (PluginFeature feature : sortedFeatures) {
            plugin.getLogger().info("Initializing: " + feature.getClass().getSimpleName()
                    + " (priority: " + feature.getPriority() + ")");
            try {
                feature.initialize(plugin, container);
                plugin.getLogger().info("Successfully initialized: " + feature.getClass().getSimpleName());
                if (feature.requiresPostWorldPass()) {
                    deferredFeatures.add(feature);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("FAILED to initialize: " + feature.getClass().getSimpleName()
                        + " - " + e.getMessage());
                e.printStackTrace();
            }
        }
        plugin.getLogger().info("=== END FEATURE INITIALIZATION ===");

        if (!deferredFeatures.isEmpty()) {
            scheduleDeferredInitialization(plugin, container, deferredFeatures);
        }
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

    private static void scheduleDeferredInitialization(JavaPlugin plugin,
                                                       DependencyContainer container,
                                                       List<PluginFeature> deferredFeatures) {
        DeferredFeatureCoordinator coordinator = new DeferredFeatureCoordinator(plugin, container, deferredFeatures);
        plugin.getServer().getPluginManager().registerEvents(coordinator, plugin);
        plugin.getServer().getScheduler().runTaskLater(plugin, coordinator::runDeferredPass, 40L);
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

    private static final class DeferredFeatureCoordinator implements Listener {
        private final JavaPlugin plugin;
        private final DependencyContainer container;
        private final List<PluginFeature> deferredFeatures;
        private final AtomicBoolean ran = new AtomicBoolean(false);

        private DeferredFeatureCoordinator(JavaPlugin plugin,
                                           DependencyContainer container,
                                           List<PluginFeature> deferredFeatures) {
            this.plugin = plugin;
            this.container = container;
            this.deferredFeatures = List.copyOf(deferredFeatures);
        }

        @EventHandler
        public void onServerLoad(ServerLoadEvent event) {
            try {
                if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
                    runDeferredPass();
                }
            } catch (NoSuchMethodError ignored) {
                runDeferredPass();
            }
        }

        private void runDeferredPass() {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            plugin.getLogger().info("=== FEATURE POST-WORLD INITIALIZATION ===");
            for (PluginFeature feature : deferredFeatures) {
                plugin.getLogger().info("Post-world initializing: " + feature.getClass().getSimpleName());
                try {
                    feature.postWorldInitialize(plugin, container);
                    plugin.getLogger().info("Post-world ready: " + feature.getClass().getSimpleName());
                } catch (Exception ex) {
                    plugin.getLogger().severe("FAILED post-world init: " + feature.getClass().getSimpleName()
                            + " - " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
            plugin.getLogger().info("=== END FEATURE POST-WORLD INITIALIZATION ===");
            ServerLoadEvent.getHandlerList().unregister(this);
        }
    }
}
