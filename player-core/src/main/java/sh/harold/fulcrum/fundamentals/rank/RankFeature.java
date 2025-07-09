package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.playerdata.StorageManager;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.model.MonthlyRankData;

/**
 * Plugin feature that registers the rank system with the platform.
 * Handles schema registration and service initialization.
 */
public final class RankFeature implements PluginFeature {

    private static RankManager rankManager;
    private static RankDisplayManager displayManager;
    private static RankEventListener eventListener;
    private static RankExpirationTask expirationTask;

    @Override
    public void initialize(JavaPlugin plugin) {
        // Register the MonthlyRankData schema with the data registry
        PlayerDataRegistry.registerSchema(
                new AutoTableSchema<>(MonthlyRankData.class),
                StorageManager.getStructuredBackend()
        );
        plugin.getLogger().info("[RankFeature] Registered MonthlyRankData schema.");

        // Initialize the rank manager
        rankManager = new RankManager();
        plugin.getLogger().info("[RankFeature] Initialized RankManager.");

        // Initialize the display manager
        displayManager = new RankDisplayManager(rankManager, plugin);
        plugin.getLogger().info("[RankFeature] Initialized RankDisplayManager.");

        // Initialize and register the event listener
        eventListener = new RankEventListener(displayManager);
        plugin.getServer().getPluginManager().registerEvents(eventListener, plugin);
        plugin.getLogger().info("[RankFeature] Registered RankEventListener.");

        // Initialize and start the expiration task
        expirationTask = new RankExpirationTask(plugin);
        expirationTask.start();
        plugin.getLogger().info("[RankFeature] Started RankExpirationTask.");

        // Register rank commands using Paper command API
        CommandRegistrar.register(new RankCommands().build());
        plugin.getLogger().info("[RankFeature] Registered rank commands.");

        // Update all online players' displays on startup
        displayManager.updateAllPlayerTablists().thenRun(() -> {
            plugin.getLogger().info("[RankFeature] Updated all online player displays.");
        });

        // Note: IdentityData schema is already registered by IdentityFeature
        // The rank system enhances the existing IdentityData with proper enum types
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
        displayManager = null;
        eventListener = null;
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
     * Get the global rank display manager instance.
     * @return The RankDisplayManager implementation
     */
    public static RankDisplayManager getRankDisplayManager() {
        if (displayManager == null) {
            throw new IllegalStateException("RankFeature has not been initialized yet");
        }
        return displayManager;
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
        return rankManager != null && displayManager != null && expirationTask != null;
    }
}