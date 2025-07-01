package sh.harold.fulcrum.feature.identity;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.playerdata.StorageManager;
import sh.harold.fulcrum.lifecycle.PluginFeature;


public final class IdentityFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        PlayerDataRegistry.registerSchema(
                new AutoTableSchema<>(IdentityData.class),
                StorageManager.getStructuredBackend()           // or getDocumentBackend() for JSON
        );
        plugin.getLogger().info("[IdentityFeature] Registered IdentityData schema.");
        // Register the IdentityListener for player join events
        plugin.getServer().getPluginManager().registerEvents(new IdentityListener(), plugin);
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }
}
