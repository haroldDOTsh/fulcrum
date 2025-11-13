package sh.harold.fulcrum.environment;

import java.util.Objects;
import java.util.Optional;

/**
 * Simple value object describing the ENVIRONMENT file contents.
 *
 * @param role       Environment role identifier (first non-empty line)
 * @param ipOverride Optional IP address override (second non-empty line)
 */
public record EnvironmentFileSettings(String role, Optional<String> ipOverride) {

    public EnvironmentFileSettings {
        Objects.requireNonNull(role, "role");
        role = role.trim();
        if (role.isEmpty()) {
            throw new IllegalArgumentException("Environment role must not be blank");
        }

        if (ipOverride == null) {
            ipOverride = Optional.empty();
        } else {
            ipOverride = ipOverride.map(String::trim).filter(line -> !line.isEmpty());
        }
    }
}
