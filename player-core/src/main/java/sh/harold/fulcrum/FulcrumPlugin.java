package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.fundamentals.gamemode.GamemodeFeature;
import sh.harold.fulcrum.fundamentals.identity.IdentityFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.module.ModuleManager;

public final class FulcrumPlugin extends JavaPlugin {
    private ModuleManager moduleManager;
    private FulcrumPlatform platform;

    @Override
    public void onEnable() {
        CommandRegistrar.hook(this);

        FeatureManager.register(new PlayerDataFeature());
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new IdentityFeature());
        FeatureManager.register(new GamemodeFeature());

        FeatureManager.initializeAll(this);

        try {
            String role = EnvironmentSelector.loadRole(new java.io.File("."));
            RuntimeEnvironment env = EnvironmentLoader.load(this);
            java.util.List<String> allowedModules = env.getModulesFor(role);

            getLogger().info("Fulcrum runtime role: " + role);
            getLogger().info("Modules for this role: " + allowedModules);

            this.platform = new FulcrumPlatform();
            this.moduleManager = new ModuleManager(getLogger());
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
