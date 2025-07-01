package sh.harold.fulcrum.feature.gamemode;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class GamemodeFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin) {

        plugin.getLogger().info("Registered Gamemode Commands");
        CommandRegistrar.register(new GamemodeCommand().build());
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }
}
