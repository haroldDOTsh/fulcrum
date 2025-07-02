package sh.harold.fulcrum.module;

import sh.harold.fulcrum.module.ModuleMetadata;

/**
 * Base class for external plugin modules.
 * Logic for lifecycle and registration will be added later.
 */
public abstract class CoreModule {
    // Metadata for this module
    public abstract ModuleMetadata metadata();
    // ...future lifecycle methods...
}
