package sh.harold.fulcrum.control.queue;

import java.util.Objects;

final class ControlQueueStrings {
    private ControlQueueStrings() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
