package sh.harold.fulcrum.api.menu;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;


public class MenuFeature implements PluginFeature {


    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
 
    }

    @Override
    public void shutdown() {

    }
    
    @Override
    public int getPriority() {
        return 25; // Load after message-api (priority 1) but before other features
    }
}