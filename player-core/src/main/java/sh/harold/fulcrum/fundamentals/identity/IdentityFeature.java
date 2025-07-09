package sh.harold.fulcrum.fundamentals.identity;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.playerdata.StorageManager;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.PluginFeature;


public final class IdentityFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        plugin.getLogger().info("[IdentityFeature] Starting initialization... (Priority: " + getPriority() + ")");
        
        plugin.getLogger().info("[IdentityFeature] Attempting to access StorageManager.getStructuredBackend()...");
        try {
            PlayerDataRegistry.registerSchema(
                    new AutoTableSchema<>(IdentityData.class),
                    StorageManager.getStructuredBackend()           // or getDocumentBackend() for JSON
            );
            plugin.getLogger().info("[IdentityFeature] Successfully registered IdentityData schema.");
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("[IdentityFeature] FAILED to access StorageManager: " + e.getMessage());
            throw e;
        }
        // Register the IdentityListener for player join events
        plugin.getServer().getPluginManager().registerEvents(new IdentityListener(), plugin);

        CommandRegistrar.register(new TestCommand().build());
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }
    
    @Override
    public int getPriority() {
        // Identity feature depends on PlayerData, so should initialize after it
        return 50;
    }
}
