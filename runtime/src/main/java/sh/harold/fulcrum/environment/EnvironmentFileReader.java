package sh.harold.fulcrum.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Utility for reading the ENVIRONMENT file from the Paper server root.
 */
public final class EnvironmentFileReader {
    public static final String FILE_NAME = "ENVIRONMENT";

    private EnvironmentFileReader() {
    }

    /**
     * Reads the ENVIRONMENT file from a server root directory.
     *
     * @param serverRoot Root directory (typically the Paper server root)
     * @return Optional settings when the file exists and contains a role
     * @throws IOException if reading the file fails
     */
    public static Optional<EnvironmentFileSettings> read(Path serverRoot) throws IOException {
        Path environmentFile = serverRoot.resolve(FILE_NAME);
        if (Files.notExists(environmentFile)) {
            return Optional.empty();
        }

        List<String> lines = Files.readAllLines(environmentFile);
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }

        if (values.isEmpty()) {
            return Optional.empty();
        }

        String role = values.get(0);
        Optional<String> ipOverride = values.size() > 1 ? Optional.of(values.get(1)) : Optional.empty();
        return Optional.of(new EnvironmentFileSettings(role, ipOverride));
    }
}
