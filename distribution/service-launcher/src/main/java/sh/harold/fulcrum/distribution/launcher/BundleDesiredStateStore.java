package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class BundleDesiredStateStore {
    static final String FILE_NAME = "bundles.json";

    private final Path stateDir;

    BundleDesiredStateStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    BundleDesiredState read() {
        Path file = file();
        if (!Files.exists(file)) {
            return BundleDesiredState.empty();
        }
        try {
            return BundleDesiredState.fromJson(Files.readString(file));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle desired state", exception);
        }
    }

    Path write(BundleDesiredState state) {
        try {
            Files.createDirectories(stateDir);
            Path file = file();
            Files.writeString(file, state.toJson());
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write bundle desired state", exception);
        }
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }
}
