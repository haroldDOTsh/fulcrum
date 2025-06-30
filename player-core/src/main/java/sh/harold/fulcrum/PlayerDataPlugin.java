package sh.harold.fulcrum;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.message.MessageFeature;
import sh.harold.fulcrum.api.playerdata.PlayerDataFeature;
import sh.harold.fulcrum.command.runtime.CommandFeature;
import sh.harold.fulcrum.feature.identity.IdentityFeature;
import sh.harold.fulcrum.lifecycle.FeatureManager;

public final class PlayerDataPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        FeatureManager.register(new PlayerDataFeature());
        FeatureManager.register(new MessageFeature());
        FeatureManager.register(new IdentityFeature());
        FeatureManager.register(new CommandFeature());

        FeatureManager.initializeAll(this);
    }

    @Override
    public void onDisable() {
        FeatureManager.shutdownAll();
    }
}
