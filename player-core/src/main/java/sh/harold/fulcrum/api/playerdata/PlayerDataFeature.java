package sh.harold.fulcrum.api.playerdata;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.dirty.DirtyDataCache;
import sh.harold.fulcrum.api.data.dirty.DirtyDataManager;
import sh.harold.fulcrum.api.data.registry.PlayerStorageManager;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.cache.DirtyCacheFactory;
import sh.harold.fulcrum.runtime.config.DirtyCacheConfig;
import sh.harold.fulcrum.runtime.config.YamlConfigLoader;

import java.io.File;
import java.time.Duration;

public class PlayerDataFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {
        plugin.getLogger().info("[PlayerDataFeature] Initializing StorageManager... (Priority: " + getPriority() + ")");
        StorageManager.initialize(plugin);
        plugin.getLogger().info("[PlayerDataFeature] StorageManager initialized successfully");

        // Initialize dirty data tracking system
        initializeDirtyDataSystem(plugin);

        plugin.getServer().getPluginManager().registerEvents(new PlayerDataLifecycleListener(), plugin);
    }

    /**
     * Initializes the dirty data tracking system with configurable cache implementation.
     * Supports both in-memory and Redis-based caching with automatic fallback.
     */
    private void initializeDirtyDataSystem(JavaPlugin plugin) {
        try {
            plugin.getLogger().info("[PlayerDataFeature] Initializing dirty data tracking system...");

            // Load cache configuration
            DirtyCacheConfig cacheConfig = loadCacheConfiguration(plugin);
            plugin.getLogger().info("[PlayerDataFeature] Cache configuration loaded: " + cacheConfig.getCacheType());

            // Create cache instance based on configuration
            DirtyDataCache cache = DirtyCacheFactory.createCache(cacheConfig, plugin);

            // Wrap cache with monitoring if needed
            cache = DirtyCacheFactory.createMonitoredCache(cache, cacheConfig, plugin.getLogger());

            // Initialize DirtyDataManager with the configured cache
            DirtyDataManager.initialize(cache);

            // Configure PlayerStorageManager for dirty data tracking
            PlayerStorageManager.StorageManagerConfig config = new PlayerStorageManager.StorageManagerConfig();
            config.dirtyTrackingEnabled = true;
            config.persistenceInterval = Duration.ofMinutes(5); // Save dirty data every 5 minutes
            config.eventBasedPersistence = true; // Enable event-based persistence
            config.timeBasedPersistence = true; // Enable time-based persistence

            PlayerStorageManager.initialize(config);

            plugin.getLogger().info("[PlayerDataFeature] Dirty data tracking system initialized successfully");
        } catch (Exception e) {
            plugin.getLogger().severe("[PlayerDataFeature] Failed to initialize dirty data tracking system: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Loads cache configuration from YAML file.
     * First tries to load from plugin data folder, then falls back to bundled config.
     */
    private DirtyCacheConfig loadCacheConfiguration(JavaPlugin plugin) {
        YamlConfigLoader configLoader = new YamlConfigLoader();

        // Try to load from plugin data folder first
        File configFile = new File(plugin.getDataFolder(), "cache-config.yml");
        if (configFile.exists()) {
            plugin.getLogger().info("[PlayerDataFeature] Loading cache configuration from: " + configFile.getAbsolutePath());
            return configLoader.loadDirtyCacheConfig(configFile.getAbsolutePath());
        }

        // Fall back to bundled configuration
        plugin.getLogger().info("[PlayerDataFeature] Loading default cache configuration from resources");
        return configLoader.loadDirtyCacheConfigFromResource("cache-config.yml");
    }

    @Override
    public int getPriority() {
        // Core data storage should initialize first
        return 10;
    }

    @Override
    public void shutdown() {
        try {
            // Cleanup dirty data system
            if (DirtyDataManager.isInitialized()) {
                DirtyDataManager.shutdown();
            }
            PlayerStorageManager.shutdown();
        } catch (Exception e) {
            System.err.println("Error during PlayerDataFeature shutdown: " + e.getMessage());
        }
    }
}
