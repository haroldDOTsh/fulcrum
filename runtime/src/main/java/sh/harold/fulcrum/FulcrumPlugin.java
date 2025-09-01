package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;

import sh.harold.fulcrum.api.chat.impl.ChatFormatFeature;
import sh.harold.fulcrum.api.menu.impl.MenuFeature;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.message.impl.scoreboard.ScoreboardFeature;
import sh.harold.fulcrum.api.module.FulcrumPlatform;
import sh.harold.fulcrum.api.module.FulcrumPlatformHolder;
import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.EnvironmentConfigParser;
import sh.harold.fulcrum.fundamentals.data.DataAPIFeature;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.messagebus.MessageBusFeature;
import sh.harold.fulcrum.fundamentals.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.fundamentals.rank.RankFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.api.module.impl.ModuleFeature;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleVerificationManager;

public final class FulcrumPlugin extends JavaPlugin {
    private ModuleManager moduleManager;
    private DependencyContainer container;
    private ModuleVerificationManager verificationManager;

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Initialize dependency container
        container = new DependencyContainer();

        CommandRegistrar.hook(this);

        // Register features (order matters - dependencies first)
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new MessageBusFeature());
        FeatureManager.register(new ServerLifecycleFeature());
        FeatureManager.register(new DataAPIFeature()); // Register DataAPI before PlayerData
        FeatureManager.register(new PlayerDataFeature()); // Depends on DataAPI
        FeatureManager.register(new RankFeature()); // Register Rank system after DataAPI
        FeatureManager.register(new ChatFormatFeature()); // Register Chat formatting after Rank
        FeatureManager.register(new ModuleFeature());
        FeatureManager.register(new GamemodeFeature());
        FeatureManager.register(new ScoreboardFeature());
        FeatureManager.register(new MenuFeature());

        // Initialize all features with dependency injection
        FeatureManager.initializeAll(this, container);

        // Environment detection was handled during bootstrap phase
        getLogger().info("Fulcrum starting with role-based module detection");
        
        // Load environment configuration to verify modules later
        EnvironmentConfigParser configParser = new EnvironmentConfigParser();
        EnvironmentConfig environmentConfig = configParser.loadDefaultConfiguration();

        // Create service locator and platform
        ServiceLocatorImpl serviceLocator = new ServiceLocatorImpl(container);
        FulcrumPlatform platform = new FulcrumPlatform(serviceLocator);
        this.moduleManager = new ModuleManager(getLogger(), this);
        
        // Initialize FulcrumPlatformHolder to make platform accessible to external modules
        FulcrumPlatformHolder.initialize(platform);

        // Register ModuleManager in the container
        container.register(ModuleManager.class, moduleManager);

        // Re-initialize ModuleFeature now that ModuleManager is available
        ModuleFeature moduleFeature = FeatureManager.getFeature(ModuleFeature.class);
        if (moduleFeature != null && !moduleFeature.areCommandsRegistered()) {
            moduleFeature.initialize(this, container);
        }

        // Note: Module loading is handled by each module's bootstrap phase
        // External modules use BootstrapContextHolder for identification
        
        // Enable module verification to check that expected modules are loaded
        this.verificationManager = new ModuleVerificationManager(getLogger(), environmentConfig, this);
        verificationManager.register();
        
        getLogger().info("Fulcrum started successfully");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
    }
    
}
