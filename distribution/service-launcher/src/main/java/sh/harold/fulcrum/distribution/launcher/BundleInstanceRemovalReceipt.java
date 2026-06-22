package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleInstanceRemovalReceipt(
        String status,
        String reason,
        Optional<String> instanceId,
        Optional<String> registrationReceiptId) {
    BundleInstanceRemovalReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        instanceId = instanceId == null ? Optional.empty() : instanceId.map(value -> requireNonBlank(value, "instanceId"));
        registrationReceiptId = registrationReceiptId == null ? Optional.empty() : registrationReceiptId
                .map(value -> requireNonBlank(value, "registrationReceiptId"));
    }

    static BundleInstanceRemovalReceipt removed(
            Optional<String> instanceId,
            Optional<String> registrationReceiptId,
            String reason) {
        return new BundleInstanceRemovalReceipt("REMOVED", reason, instanceId, registrationReceiptId);
    }

    static BundleInstanceRemovalReceipt blocked(
            Optional<String> instanceId,
            Optional<String> registrationReceiptId,
            String reason) {
        return new BundleInstanceRemovalReceipt("BLOCKED", reason, instanceId, registrationReceiptId);
    }

    boolean removed() {
        return status.equals("REMOVED");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
