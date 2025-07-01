package sh.harold.fulcrum.command;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Utility for developer-friendly, annotation-based command registration.
 * <p>
 * Scans the calling plugin's classloader for @Command-annotated classes implementing CommandExecutor,
 * instantiates them, and registers them with the Fulcrum command system.
 * <p>
 * Usage:
 * <pre>
 *     FulcrumCommands.register(this);
 * </pre>
 */
public final class FulcrumCommands {
    private static volatile FulcrumCommandsProvider provider;

    private FulcrumCommands() {
    }

    /**
     * Called by the runtime to provide the actual registration implementation.
     */
    public static void setProvider(FulcrumCommandsProvider impl) {
        provider = impl;
    }

    /**
     * Scans the plugin's own classes for @Command-annotated CommandExecutor implementations and registers them.
     *
     * @param plugin the plugin whose commands to register
     */
    public static void register(JavaPlugin plugin) {
        if (provider == null) {
            throw new IllegalStateException("No FulcrumCommandsProvider registered");
        }
        provider.register(plugin);
    }
}
