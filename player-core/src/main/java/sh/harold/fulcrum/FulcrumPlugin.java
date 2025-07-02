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

        // --- ENVIRONMENT/ENVIRONMENT.YML TEST STUB ---
        try {
            String role = EnvironmentSelector.loadRole(new java.io.File("."));
            getLogger().info("Fulcrum runtime role: " + role);

            RuntimeEnvironment env = EnvironmentLoader.load(this);
            java.util.List<String> modules = env.getModulesFor(role);

            getLogger().info("Modules for this role: " + modules);
        } catch (java.io.IOException e) {
            getLogger().severe("Failed to load runtime environment: " + e.getMessage());
            getServer().shutdown(); // Optional safety
        }

        // Initialize platform and module system
        this.platform = new FulcrumPlatform();
        this.moduleManager = new ModuleManager(getLogger());
        moduleManager.loadAll(this, platform);
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        FeatureManager.shutdownAll();
    }
}
