package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;
import java.util.UUID;

final class BundleLaunchNonces {
    private BundleLaunchNonces() {
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }

    static String require(String value) {
        String checked = Objects.requireNonNull(value, "launchNonce").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("launchNonce must not be blank");
        }
        return checked;
    }
}
