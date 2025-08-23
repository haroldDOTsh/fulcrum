package sh.harold.fulcrum.api.module.impl;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.module.FulcrumModule;

import java.util.List;

/**
 * Runtime metadata for a loaded Fulcrum module.
 */
public record ModuleMetadata(
        String name,
        List<String> dependsOn,
        String description,
        JavaPlugin plugin,
        FulcrumModule instance
) {
}
