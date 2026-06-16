package sh.harold.fulcrum.control.route;

import java.util.Objects;

final class ControlRouteStrings {
    private ControlRouteStrings() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
