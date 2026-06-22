package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleRuntimeStartReceipt(
        String status,
        String reason,
        Optional<String> runtimeEvidence) {
    BundleRuntimeStartReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        runtimeEvidence = runtimeEvidence == null ? Optional.empty() : runtimeEvidence
                .map(value -> requireNonBlank(value, "runtimeEvidence"));
    }

    static BundleRuntimeStartReceipt renderOnly() {
        return new BundleRuntimeStartReceipt(
                "RENDERED",
                "instance-rendered-start-pending",
                Optional.empty());
    }

    static BundleRuntimeStartReceipt started(String runtimeEvidence) {
        return new BundleRuntimeStartReceipt(
                "STARTED",
                "runtime-started-registration-pending",
                Optional.of(runtimeEvidence));
    }

    static BundleRuntimeStartReceipt failed(String reason, String runtimeEvidence) {
        return new BundleRuntimeStartReceipt(
                "START_FAILED",
                reason,
                Optional.of(runtimeEvidence));
    }

    boolean renderedOnly() {
        return status.equals("RENDERED") && runtimeEvidence.isEmpty();
    }

    boolean started() {
        return status.equals("STARTED") && runtimeEvidence.isPresent();
    }

    boolean failed() {
        return status.equals("START_FAILED") && runtimeEvidence.isPresent();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
