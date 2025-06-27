package sh.harold.fulcrum.lifecycle;

import org.bukkit.plugin.java.JavaPlugin;

public interface PluginFeature {
    void initialize(JavaPlugin plugin);

    default void shutdown() {
        // This method is optional
    }
}
