package sh.harold.fulcrum.module;

import java.util.List;

/**
 * Metadata for a CoreModule.
 */
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.FulcrumModule;

/**
 * Runtime metadata for a loaded Fulcrum module.
 */
public record ModuleMetadata(
    String name,
    List<String> dependsOn,
    String description,
    JavaPlugin plugin,
    FulcrumModule instance
) {}
