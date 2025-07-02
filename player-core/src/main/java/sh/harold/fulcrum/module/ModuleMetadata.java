package sh.harold.fulcrum.module;

import java.util.List;

/**
 * Metadata for a CoreModule.
 */
public record ModuleMetadata(
    String name,
    String description,
    List<String> authors,
    String version
) {}
