package sh.harold.fulcrum;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.impl.ChatFormatFeature;
import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.EnvironmentConfigParser;
import sh.harold.fulcrum.api.menu.impl.MenuFeature;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.message.impl.scoreboard.ScoreboardFeature;
import sh.harold.fulcrum.api.module.FulcrumPlatform;
import sh.harold.fulcrum.api.module.FulcrumPlatformHolder;
import sh.harold.fulcrum.api.module.impl.ModuleFeature;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleVerificationManager;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagFeature;
import sh.harold.fulcrum.fundamentals.chat.ChatChannelFeature;
import sh.harold.fulcrum.fundamentals.data.DataAPIFeature;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.lifecycle.JoinMessageFeature;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.messagebus.MessageBusFeature;
import sh.harold.fulcrum.fundamentals.minigame.debug.DebugMinigameFeature;
import sh.harold.fulcrum.fundamentals.network.NetworkConfigFeature;
import sh.harold.fulcrum.fundamentals.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.fundamentals.rank.RankFeature;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyService;
import sh.harold.fulcrum.fundamentals.world.WorldFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.MinigameEngineFeature;
import sh.harold.fulcrum.minigame.MinigameModuleRegistrar;

public final class FulcrumPlugin extends JavaPlugin {
    private static final int CONFIG_VERSION = 2;

    private ModuleManager moduleManager;
    private SlotFamilyService slotFamilyService;
    private DependencyContainer container;
    private ModuleVerificationManager verificationManager;
    private MinigameModuleRegistrar minigameModuleRegistrar;

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @Override
    public void onEnable() {
        FeatureManager.clear();
        CommandRegistrar.reset();
        ServiceLocatorImpl.reset();
        FulcrumPlatformHolder.reset();

        // Save default config if it doesn't exist
        saveDefaultConfig();
        FileConfiguration config = loadAndMigrateConfig();
        boolean debugMinigameEnabled = config.getBoolean("features.debug-minigame", false);

        // Initialize dependency container
        container = new DependencyContainer();
        ServiceLocatorImpl serviceLocator = new ServiceLocatorImpl(container);

        CommandRegistrar.hook(this);

        this.moduleManager = new ModuleManager(getLogger(), this);
        this.slotFamilyService = new SlotFamilyService(moduleManager);
        container.register(ModuleManager.class, moduleManager);
        container.register(SlotFamilyService.class, slotFamilyService);

        // Register features (order matters - dependencies first)
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new MessageBusFeature());
        FeatureManager.register(new ServerLifecycleFeature());
        FeatureManager.register(new JoinMessageFeature());
        FeatureManager.register(new DataAPIFeature()); // Register DataAPI before PlayerData
        FeatureManager.register(new sh.harold.fulcrum.fundamentals.session.PlayerSessionFeature());
        FeatureManager.register(new PlayerDataFeature()); // Depends on DataAPI & sessions
        FeatureManager.register(new NetworkConfigFeature());
        FeatureManager.register(new RankFeature()); // Register Rank system after DataAPI
        FeatureManager.register(new ChatFormatFeature()); // Register Chat formatting after Rank
        FeatureManager.register(new ChatChannelFeature());
        FeatureManager.register(new ModuleFeature());
        FeatureManager.register(new GamemodeFeature());
        FeatureManager.register(new ScoreboardFeature());
        FeatureManager.register(new MenuFeature());
        FeatureManager.register(new ActionFlagFeature());
        FeatureManager.register(new WorldFeature()); // World management with FAWE
        FeatureManager.register(new MinigameEngineFeature());
        FeatureManager.register(new sh.harold.fulcrum.fundamentals.routing.EnvironmentRoutingFeature());
        if (debugMinigameEnabled) {
            FeatureManager.register(new DebugMinigameFeature());
            getLogger().info("Debug minigame feature enabled via configuration.");
        } else {
            getLogger().info("Debug minigame feature disabled via configuration.");
        }

        // Initialize all features with dependency injection
        FeatureManager.initializeAll(this, container);

        container.getOptional(MinigameEngine.class)
                .ifPresent(engine -> {
                    this.minigameModuleRegistrar = new MinigameModuleRegistrar(this, moduleManager, slotFamilyService, engine);
                    this.minigameModuleRegistrar.initialize();
                });

        // Environment detection was handled during bootstrap phase
        getLogger().info("Fulcrum starting with role-based module detection");

        // Load environment configuration to verify modules later
        EnvironmentConfigParser configParser = new EnvironmentConfigParser();
        EnvironmentConfig environmentConfig = configParser.loadDefaultConfiguration();

        // Create service locator and platform
        FulcrumPlatform platform = new FulcrumPlatform(serviceLocator);

        // Initialize FulcrumPlatformHolder to make platform accessible to external modules
        FulcrumPlatformHolder.initialize(platform);

        // Note: Module loading is handled by each module's bootstrap phase
        // External modules use BootstrapContextHolder for identification

        // Enable module verification to check that expected modules are loaded
        this.verificationManager = new ModuleVerificationManager(getLogger(), environmentConfig, this);
        verificationManager.register();

        getLogger().info("Fulcrum started successfully");
    }

    private FileConfiguration loadAndMigrateConfig() {
        FileConfiguration config = getConfig();
        int currentVersion = config.getInt("config-version", 1);
        boolean updated = false;

        if (currentVersion < 2) {
            currentVersion = 2;
            updated = true;
        }

        ConfigurationSection features = config.getConfigurationSection("features");
        if (features == null) {
            features = config.createSection("features");
            updated = true;
        }
        if (!features.contains("debug-minigame")) {
            features.set("debug-minigame", false);
            updated = true;
        }

        if (currentVersion != CONFIG_VERSION) {
            currentVersion = CONFIG_VERSION;
            updated = true;
        }

        if (updated) {
            config.set("config-version", CONFIG_VERSION);
            saveConfig();
            reloadConfig();
            config = getConfig();
            getLogger().info("Migrated config.yml to version " + CONFIG_VERSION);
        }

        return config;
    }

    @Override
    public void onDisable() {
        if (minigameModuleRegistrar != null) {
            minigameModuleRegistrar.shutdown();
            minigameModuleRegistrar = null;
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
        CommandRegistrar.reset();
        ServiceLocatorImpl.reset();
        FulcrumPlatformHolder.reset();
        moduleManager = null;
        container = null;
        verificationManager = null;
    }

}
