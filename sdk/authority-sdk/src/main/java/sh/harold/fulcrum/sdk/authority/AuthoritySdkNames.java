package sh.harold.fulcrum.sdk.authority;

import java.util.Objects;

final class AuthoritySdkNames {
    private AuthoritySdkNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
