package sh.harold.fulcrum.host.paper;

import java.nio.file.Path;
import java.util.Objects;

public record PaperPreparedWorld(
        CachedArtifact artifact,
        Path worldDirectory,
        int fileCount) {
    public PaperPreparedWorld {
        artifact = Objects.requireNonNull(artifact, "artifact");
        worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize();
        if (fileCount <= 0) {
            throw new IllegalArgumentException("fileCount must be positive");
        }
    }
}
