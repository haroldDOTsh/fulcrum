package sh.harold.fulcrum.api;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class MenuFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        // Register menu framework, listeners, etc.
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
