package sh.harold.fulcrum.command;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Service provider interface for FulcrumCommands registration logic.
 */
public interface FulcrumCommandsProvider {
    void register(JavaPlugin plugin);
}
