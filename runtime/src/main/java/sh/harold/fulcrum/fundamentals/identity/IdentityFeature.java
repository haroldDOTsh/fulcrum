package sh.harold.fulcrum.fundamentals.identity;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.playerdata.StorageManager;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;


public final class IdentityFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        plugin.getLogger().info("[FUNDAMENTALS] Starting initialization of Player identities... (Priority: " + getPriority() + ")");
        plugin.getLogger().fine("[FUNDAMENTALS] Attempting to access StorageManager.getStructuredBackend()...");
        try {
            PlayerDataRegistry.registerSchema(
                    new AutoTableSchema<>(IdentityData.class),
                    StorageManager.getStructuredBackend()           // or getDocumentBackend() for JSON
            );
            plugin.getLogger().info("[FUNDAMENTALS] Successfully registered IdentityData schema.");
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("[FUNDAMENTALS] Identities FAILED to access StorageManager: " + e.getMessage());
            throw e;
        }
        // Register the IdentityListener for player join events
        plugin.getServer().getPluginManager().registerEvents(new IdentityListener(), plugin);

    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }

    @Override
    public int getPriority() {
        // Identity feature depends on PlayerData and MessageService, so should initialize after them
        return 50;
    }
}
