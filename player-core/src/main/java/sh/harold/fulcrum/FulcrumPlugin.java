package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.message.scoreboard.ScoreboardFeature;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.identity.IdentityFeature;
import sh.harold.fulcrum.fundamentals.rank.RankFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.module.ModuleManager;
import sh.harold.fulcrum.module.ModuleFeature;

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

        // Initialize all features with dependency injection
        FeatureManager.initializeAll(this, container);

        try {
            String role = EnvironmentSelector.loadRole(new java.io.File("."));
            RuntimeEnvironment env = EnvironmentLoader.load(this);
            java.util.List<String> allowedModules = env.getModulesFor(role);

            getLogger().info("Fulcrum runtime role: " + role);
            getLogger().info("Modules for this role: " + allowedModules);

            // Create platform with dependency container
            this.platform = new FulcrumPlatform(container);
            this.moduleManager = new ModuleManager(getLogger());
            
            // Register ModuleManager in the container
            container.register(ModuleManager.class, moduleManager);
            
            // Re-initialize ModuleFeature now that ModuleManager is available
            ModuleFeature moduleFeature = FeatureManager.getFeature(ModuleFeature.class);
            if (moduleFeature != null && !moduleFeature.areCommandsRegistered()) {
                moduleFeature.initialize(this, container);
            }
            
            moduleManager.loadModules(allowedModules, platform);
        } catch (java.io.IOException e) {
            getLogger().severe("Failed to load environment: " + e.getMessage());
            getServer().shutdown();
        }
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
    }
}
