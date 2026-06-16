package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

enum DeploymentProfile {
    SINGLE_MACHINE("single-machine"),
    SMALL_PRODUCTION("small-production"),
    LARGE_PRODUCTION("large-production");

    private final String id;
    private final String resourcePath;

    DeploymentProfile(String id) {
        this.id = id;
        this.resourcePath = "fulcrum/profiles/" + id + ".json";
    }

    String id() {
        return id;
    }

    String resourcePath() {
        return resourcePath;
    }

    ProfileDescriptor loadDescriptor(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try (var input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Deployment profile descriptor not found: " + resourcePath);
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            ProfileDescriptor descriptor = ProfileDescriptor.parse(resourcePath, json);
            if (!id.equals(descriptor.profileId())) {
                throw new IllegalStateException("Deployment profile " + resourcePath
                        + " declares profileId " + descriptor.profileId() + " instead of " + id);
            }
            return descriptor;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read deployment profile descriptor: " + resourcePath, exception);
        }
    }

    static DeploymentProfile fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(profile -> profile.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown deployment profile: " + id));
    }
}
