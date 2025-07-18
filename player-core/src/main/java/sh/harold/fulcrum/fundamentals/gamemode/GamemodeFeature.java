package sh.harold.fulcrum.fundamentals.gamemode;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public class GamemodeFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        plugin.getLogger().info("Registered Gamemode Commands");
        CommandRegistrar.register(new GamemodeCommand().build());
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }

    @Override
    public int getPriority() {
        return 60; // After MessageService (priority 1)
    }
}
