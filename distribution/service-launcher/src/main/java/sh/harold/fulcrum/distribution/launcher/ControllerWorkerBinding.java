package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;
import java.util.Optional;

record ControllerWorkerBinding(
        String controllerDomain,
        ControllerWorkerPoller poller) {
    ControllerWorkerBinding {
        controllerDomain = requireNonBlank(controllerDomain, "controllerDomain");
        poller = Objects.requireNonNull(poller, "poller");
    }

    Optional<ControllerRuntimeReceipt> handleNext() {
        return poller.handleNext();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
