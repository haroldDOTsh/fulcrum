package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;

import sh.harold.fulcrum.api.menu.impl.MenuFeature;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.message.impl.scoreboard.ScoreboardFeature;
import sh.harold.fulcrum.api.module.FulcrumPlatform;
import sh.harold.fulcrum.api.module.FulcrumPlatformHolder;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.environment.EnvironmentConfig;
import sh.harold.fulcrum.environment.EnvironmentConfigParser;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.identity.IdentityFeature;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.messagebus.MessageBusFeature;
import sh.harold.fulcrum.api.rank.impl.RankFeature;
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

        // Register features
        FeatureManager.register(new PlayerDataFeature());
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new IdentityFeature());
        FeatureManager.register(new MessageBusFeature());
        FeatureManager.register(new ServerLifecycleFeature());
        FeatureManager.register(new ModuleFeature());
        FeatureManager.register(new GamemodeFeature());
        FeatureManager.register(new RankFeature());
        FeatureManager.register(new ScoreboardFeature());
        FeatureManager.register(new MenuFeature());

        // Initialize all features with dependency injection
        FeatureManager.initializeAll(this, container);

        // Environment detection is now handled through manual context management
        getLogger().info("Fulcrum starting with context-based module detection");
        
        // Load environment configuration for module verification
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

        // Note: Module loading is now handled by each module's bootstrap phase
        // External modules use BootstrapContextHolder for identification
        
        // Setup deferred module verification
        this.verificationManager = new ModuleVerificationManager(getLogger(), environmentConfig, this);
        verificationManager.register();
        
        getLogger().info("Module verification scheduled for after server load");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
    }
    
}
