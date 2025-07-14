package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.playerdata.StorageManager;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.model.MonthlyRankData;
import sh.harold.fulcrum.api.rank.model.MonthlyRankHistoryData;

/**
 * Plugin feature that registers the rank system with the platform.
 * Handles schema registration and service initialization.
 */
public final class RankFeature implements PluginFeature {

    private static RankManager rankManager;
    private static RankExpirationTask expirationTask;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Initialize using the container
        initialize(plugin);
        
        // Register services in the dependency container
        if (rankManager != null) {
            container.register(RankService.class, rankManager);
            plugin.getLogger().info("[RankFeature] Registered RankService in dependency container.");
        }
    }
    
    @Override
    public void initialize(JavaPlugin plugin) {
        plugin.getLogger().info("[RankFeature] Starting initialization... (Priority: " + getPriority() + ")");
        
        // Register the MonthlyRankData schema with the data registry
        plugin.getLogger().info("[RankFeature] Attempting to access StorageManager.getStructuredBackend()...");
        try {
            PlayerDataRegistry.registerSchema(
                    new AutoTableSchema<>(MonthlyRankData.class),
                    StorageManager.getStructuredBackend()
            );
            plugin.getLogger().info("[RankFeature] Successfully registered MonthlyRankData schema.");
            
            // Register the new MonthlyRankHistoryData schema for historical tracking
            PlayerDataRegistry.registerSchema(
                    new AutoTableSchema<>(MonthlyRankHistoryData.class),
                    StorageManager.getStructuredBackend()
            );
            plugin.getLogger().info("[RankFeature] Successfully registered MonthlyRankHistoryData schema.");
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("[RankFeature] FAILED to access StorageManager: " + e.getMessage());
            throw e;
        }

        // Initialize the rank manager
        rankManager = new RankManager();
        plugin.getLogger().info("[RankFeature] Initialized RankManager.");

        // Initialize and start the expiration task
        expirationTask = new RankExpirationTask(plugin);
        expirationTask.start();
        plugin.getLogger().info("[RankFeature] Started RankExpirationTask.");

        // Register rank commands using Paper command API
        CommandRegistrar.register(new RankCommands().build());
        plugin.getLogger().info("[RankFeature] Registered rank commands.");

        // Note: IdentityData schema is already registered by IdentityFeature
        // The rank system enhances the existing IdentityData with proper enum types
    }
    
    @Override
    public int getPriority() {
        // Rank feature should initialize after player data (priority 50)
        return 60;
    }

    @Override
    public void shutdown() {
        // Stop the expiration task
        if (expirationTask != null) {
            expirationTask.stop();
            expirationTask = null;
        }
        
        // Clean up any resources if needed
        rankManager = null;
    }

    /**
     * Get the global rank service instance.
     * @return The RankService implementation
     */
    public static RankService getRankService() {
        if (rankManager == null) {
            throw new IllegalStateException("RankFeature has not been initialized yet");
        }
        return rankManager;
    }

    /**
     * Get the global rank expiration task instance.
     * @return The RankExpirationTask implementation
     */
    public static RankExpirationTask getRankExpirationTask() {
        if (expirationTask == null) {
            throw new IllegalStateException("RankFeature has not been initialized yet");
        }
        return expirationTask;
    }

    /**
     * Check if the rank system has been initialized.
     * @return true if the rank system is ready to use
     */
    public static boolean isInitialized() {
        return rankManager != null && expirationTask != null;
    }
}