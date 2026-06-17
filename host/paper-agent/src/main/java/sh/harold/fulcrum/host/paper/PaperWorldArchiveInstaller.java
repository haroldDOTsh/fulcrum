package sh.harold.fulcrum.host.paper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PaperWorldArchiveInstaller implements PaperWorldInstaller {
    private final Path worldDirectory;

    public PaperWorldArchiveInstaller(Path worldDirectory) {
        this.worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize();
    }

    @Override
    public PaperPreparedWorld install(CachedArtifact artifact, PaperGameServerAssignment assignment) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(assignment, "assignment");
        if (!artifact.artifactPin().equals(assignment.worldArtifact())) {
            throw new IllegalArgumentException("cached artifact does not match assignment world artifact");
        }
        Files.createDirectories(worldDirectory);
        try (var entries = Files.list(worldDirectory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalStateException("Paper world directory must be empty before install");
            }
        }

        int files = 0;
        try (InputStream input = Files.newInputStream(artifact.cachedPath());
                ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                Path target = targetPath(entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(archive, target);
                    files++;
                }
                archive.closeEntry();
            }
        }
        return new PaperPreparedWorld(artifact, worldDirectory, files);
    }

    private Path targetPath(ZipEntry entry) {
        String name = PaperArtifactNames.requireNonBlank(entry.getName(), "archive entry name");
        Path target = worldDirectory.resolve(name).normalize();
        if (!target.startsWith(worldDirectory)) {
            throw new IllegalArgumentException("World archive entry escapes the target directory: " + name);
        }
        return target;
    }
}
