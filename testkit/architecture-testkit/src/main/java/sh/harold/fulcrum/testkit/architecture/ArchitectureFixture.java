package sh.harold.fulcrum.testkit.architecture;

import java.nio.file.Path;
import java.util.Objects;

public record ArchitectureFixture(Path rootDirectory) {
    public ArchitectureFixture {
        rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
    }
}
