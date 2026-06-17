package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;

record ControllerRuntimeReceipt(
        String controllerDomain,
        String handledKey) {
    ControllerRuntimeReceipt {
        controllerDomain = requireNonBlank(controllerDomain, "controllerDomain");
        handledKey = requireNonBlank(handledKey, "handledKey");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
