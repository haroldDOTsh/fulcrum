package sh.harold.fulcrum.api.playerdata;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class PlayerDataFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        StorageManager.initialize(plugin);
        plugin.getServer().getPluginManager().registerEvents(new PlayerDataLifecycleListener(), plugin);
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
