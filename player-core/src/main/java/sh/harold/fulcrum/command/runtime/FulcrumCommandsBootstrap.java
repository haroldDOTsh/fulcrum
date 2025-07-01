package sh.harold.fulcrum.command.runtime;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.command.FulcrumCommands;

/**
 * Registers the FulcrumCommandsProvider implementation on plugin startup.
 */
public final class FulcrumCommandsBootstrap {
    private FulcrumCommandsBootstrap() {}

    public static void bootstrap(JavaPlugin plugin) {
        // Register the runtime provider implementation
        FulcrumCommands.setProvider(new FulcrumCommandsProviderImpl());
    }
}
