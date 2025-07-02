package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.feature.gamemode.GamemodeFeature;
import sh.harold.fulcrum.feature.identity.IdentityFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.FeatureManager;
import sh.harold.fulcrum.module.ModuleManager;
import sh.harold.fulcrum.FulcrumPlatform;

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
