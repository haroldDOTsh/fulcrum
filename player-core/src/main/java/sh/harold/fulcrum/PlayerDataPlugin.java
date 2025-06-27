package sh.harold.fulcrum.playerdata;

import org.bukkit.plugin.java.JavaPlugin;

import sh.harold.fulcrum.features.message.MessageFeature;
import sh.harold.fulcrum.features.PlayerDataFeature;
import sh.harold.fulcrum.lifecycle.FeatureManager;

public final class PlayerDataPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        FeatureManager.register(new PlayerDataFeature());
        FeatureManager.register(new MessageFeature());
        FeatureManager.initializeAll(this);
    }

    @Override
    public void onDisable() {
        FeatureManager.shutdownAll();
    }
}
