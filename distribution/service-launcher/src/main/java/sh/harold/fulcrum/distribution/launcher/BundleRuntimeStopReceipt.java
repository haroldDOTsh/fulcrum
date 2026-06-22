package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleRuntimeStopReceipt(
        String status,
        String reason,
        Optional<String> runtimeEvidence) {
    BundleRuntimeStopReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        runtimeEvidence = runtimeEvidence == null ? Optional.empty() : runtimeEvidence
                .map(value -> requireNonBlank(value, "runtimeEvidence"));
    }

    static BundleRuntimeStopReceipt stopped(String runtimeEvidence) {
        return new BundleRuntimeStopReceipt(
                "STOPPED",
                "runtime-stopped",
                Optional.of(runtimeEvidence));
    }

    static BundleRuntimeStopReceipt skipped(String reason) {
        return new BundleRuntimeStopReceipt(
                "SKIPPED",
                reason,
                Optional.empty());
    }

    static BundleRuntimeStopReceipt failed(String reason, String runtimeEvidence) {
        return new BundleRuntimeStopReceipt(
                "STOP_FAILED",
                reason,
                Optional.of(runtimeEvidence));
    }

    boolean stoppedOrSkipped() {
        return status.equals("STOPPED") || status.equals("SKIPPED");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
