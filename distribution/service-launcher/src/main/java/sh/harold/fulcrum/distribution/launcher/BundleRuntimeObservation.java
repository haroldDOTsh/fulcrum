package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleRuntimeObservation(
        String status,
        String reason,
        Optional<String> runtimeEvidence) {
    BundleRuntimeObservation {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        runtimeEvidence = runtimeEvidence == null ? Optional.empty() : runtimeEvidence
                .map(value -> requireNonBlank(value, "runtimeEvidence"));
    }

    static BundleRuntimeObservation fromRecord(BundleInstanceRecord record) {
        BundleInstanceRecord checked = java.util.Objects.requireNonNull(record, "record");
        if (checked.status().equals("RUNNING") || checked.status().equals("STARTED")) {
            return new BundleRuntimeObservation(
                    "UNOBSERVED_" + checked.status(),
                    "runtime-observation-unavailable",
                    Optional.empty());
        }
        return new BundleRuntimeObservation(
                checked.status(),
                "runtime-observation-not-required",
                checked.runtimeEvidence());
    }

    static BundleRuntimeObservation processRunning(BundleInstanceRecord record, String runtimeEvidence) {
        BundleInstanceRecord checked = java.util.Objects.requireNonNull(record, "record");
        return new BundleRuntimeObservation(
                checked.status().equals("RUNNING") ? "RUNNING" : "STARTED",
                "runtime-process-running",
                Optional.of(runtimeEvidence));
    }

    static BundleRuntimeObservation stopped(String runtimeEvidence) {
        return new BundleRuntimeObservation(
                "STOPPED",
                "runtime-process-not-running",
                Optional.of(runtimeEvidence));
    }

    static BundleRuntimeObservation failed(String reason, String runtimeEvidence) {
        return new BundleRuntimeObservation(
                "RUNTIME_STATUS_FAILED",
                reason,
                Optional.of(runtimeEvidence));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
