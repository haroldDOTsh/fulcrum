package sh.harold.fulcrum.fundamentals.staff;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.staff.command.KaboomCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public final class KaboomFeature implements PluginFeature {

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        plugin.getLogger().info("[FUNDAMENTALS] Registered Kaboom Command");
        CommandRegistrar.register(new KaboomCommand().build());
    }

    @Override
    public void shutdown() {
        // No shutdown hooks required
    }

    @Override
    public int getPriority() {
        return 60;
    }
}
