package sh.harold.fulcrum.adapters.objectstorage;

import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.util.Optional;

public interface ObjectStorageAdapter {
    StoredObject put(ArtifactPin artifactPin, byte[] bytes) throws IOException;

    Optional<byte[]> read(ArtifactObjectAddress address) throws IOException;

    boolean exists(ArtifactObjectAddress address) throws IOException;
}
