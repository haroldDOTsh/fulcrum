package sh.harold.fulcrum.data.authority;

import java.util.Objects;

public record AuthorityEmission(
        AuthorityEmissionKind kind,
        String key,
        String payload) {
    public AuthorityEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = requireNonBlank(key, "key");
        payload = Objects.requireNonNull(payload, "payload");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
