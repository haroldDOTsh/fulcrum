package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

@FunctionalInterface
interface ControllerWorkerPoller {
    Optional<ControllerRuntimeReceipt> handleNext();
}
