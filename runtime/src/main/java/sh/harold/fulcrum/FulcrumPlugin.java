package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;

import sh.harold.fulcrum.api.menu.MenuFeature;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardFeature;
import sh.harold.fulcrum.api.module.FulcrumPlatform;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.environment.SimpleEnvironmentDetector;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.identity.IdentityFeature;
import sh.harold.fulcrum.fundamentals.rank.RankFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.module.ModuleFeature;
import sh.harold.fulcrum.module.ModuleManager;

public final class FulcrumPlugin extends JavaPlugin {
    private ModuleManager moduleManager;
    private FulcrumPlatform platform;
    private DependencyContainer container;

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @Override
    public void onEnable() {
        // Initialize dependency container
        container = new DependencyContainer();

        CommandRegistrar.hook(this);

        // Register features
        FeatureManager.register(new PlayerDataFeature());
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new IdentityFeature());
        FeatureManager.register(new ModuleFeature());
        FeatureManager.register(new GamemodeFeature());
        FeatureManager.register(new RankFeature());
        FeatureManager.register(new ScoreboardFeature());
        FeatureManager.register(new MenuFeature());

        // Initialize all features with dependency injection
        FeatureManager.initializeAll(this, container);

        // Detect current environment using the new static method
        String currentEnvironment = SimpleEnvironmentDetector.detectEnvironment();
        getLogger().info("Fulcrum detected environment: " + currentEnvironment);

        // Create platform with service locator
        ServiceLocatorImpl serviceLocator = new ServiceLocatorImpl(container);
        this.platform = new FulcrumPlatform(serviceLocator);
        this.moduleManager = new ModuleManager(getLogger(), this);

        // Register ModuleManager in the container
        container.register(ModuleManager.class, moduleManager);

        // Re-initialize ModuleFeature now that ModuleManager is available
        ModuleFeature moduleFeature = FeatureManager.getFeature(ModuleFeature.class);
        if (moduleFeature != null && !moduleFeature.areCommandsRegistered()) {
            moduleFeature.initialize(this, container);
        }

        // Note: Module loading is now handled by each module's bootstrap phase
        // External modules self-disable using FulcrumEnvironment.isThisModuleEnabled() during bootstrap
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
    }
}
