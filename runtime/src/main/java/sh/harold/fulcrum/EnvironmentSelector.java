package sh.harold.fulcrum;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Selects the current runtime environment role from the ENVIRONMENT file in the server root.
 */
public final class EnvironmentSelector {
    private EnvironmentSelector() {
    }

    public static String loadRole(File serverRoot) throws IOException {
        File file = new File(serverRoot, "ENVIRONMENT");
        if (!file.exists()) {
            throw new FileNotFoundException("Missing ENVIRONMENT file in server root.");
        }
        return Files.readString(file.toPath()).trim();
    }
}
