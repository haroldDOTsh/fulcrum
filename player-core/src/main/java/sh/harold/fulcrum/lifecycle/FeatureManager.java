package sh.harold.fulcrum.lifecycle;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class FeatureManager {
    private static final List<PluginFeature> features = new ArrayList<>();

    private FeatureManager() {
        // Prevent instantiation
    }

    public static void register(PluginFeature feature) {
        features.add(feature);
    }

    public static void initializeAll(JavaPlugin plugin) {
        for (PluginFeature feature : features) {
            try {
                feature.initialize(plugin);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize feature: " + feature.getClass().getSimpleName());
                e.printStackTrace();
            }
        }
    }

    public static void shutdownAll() {
        for (PluginFeature feature : features) {
            try {
                feature.shutdown();
            } catch (Exception e) {
                // In a real plugin, this should be logged.
                e.printStackTrace();
            }
        }
    }
}
