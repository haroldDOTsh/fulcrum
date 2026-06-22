package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleReadinessReceipt(
        String status,
        String reason,
        Optional<String> registrationReceiptId,
        Optional<String> registrationEvidence) {
    BundleReadinessReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        registrationReceiptId = registrationReceiptId == null ? Optional.empty() : registrationReceiptId
                .map(value -> requireNonBlank(value, "registrationReceiptId"));
        registrationEvidence = registrationEvidence == null ? Optional.empty() : registrationEvidence
                .map(value -> requireNonBlank(value, "registrationEvidence"));
    }

    static BundleReadinessReceipt ready(String registrationReceiptId, String registrationEvidence) {
        return new BundleReadinessReceipt(
                "READY",
                "backend-self-registered-and-ready",
                Optional.of(registrationReceiptId),
                Optional.of(registrationEvidence));
    }

    static BundleReadinessReceipt pending(String reason) {
        return new BundleReadinessReceipt(
                "PENDING",
                reason,
                Optional.empty(),
                Optional.empty());
    }

    static BundleReadinessReceipt rejected(String reason, String registrationEvidence) {
        return new BundleReadinessReceipt(
                "REJECTED",
                reason,
                Optional.empty(),
                Optional.of(registrationEvidence));
    }

    boolean ready() {
        return status.equals("READY") && registrationReceiptId.isPresent() && registrationEvidence.isPresent();
    }

    boolean rejected() {
        return status.equals("REJECTED") && registrationEvidence.isPresent();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
