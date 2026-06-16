package sh.harold.fulcrum.control.fault;

import java.util.Objects;

final class ControlFaultStrings {
    private ControlFaultStrings() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
