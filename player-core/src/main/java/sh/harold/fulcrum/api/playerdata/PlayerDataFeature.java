package sh.harold.fulcrum.api.playerdata;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class PlayerDataFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        plugin.getLogger().info("[PlayerDataFeature] Initializing StorageManager... (Priority: " + getPriority() + ")");
        StorageManager.initialize(plugin);
        plugin.getLogger().info("[PlayerDataFeature] StorageManager initialized successfully");
        plugin.getServer().getPluginManager().registerEvents(new PlayerDataLifecycleListener(), plugin);
    }
    
    @Override
    public int getPriority() {
        // Core data storage should initialize first
        return 10;
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
