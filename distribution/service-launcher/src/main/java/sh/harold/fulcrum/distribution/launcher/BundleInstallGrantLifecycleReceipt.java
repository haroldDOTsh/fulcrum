package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleInstallGrantLifecycleReceipt(
        String status,
        String reason,
        Optional<String> grantFingerprint,
        Optional<String> instanceId,
        Optional<String> credentialRef,
        Optional<String> evidence) {
    BundleInstallGrantLifecycleReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        grantFingerprint = grantFingerprint == null
                ? Optional.empty()
                : grantFingerprint.map(value -> requireNonBlank(value, "grantFingerprint"));
        instanceId = instanceId == null
                ? Optional.empty()
                : instanceId.map(value -> requireNonBlank(value, "instanceId"));
        credentialRef = credentialRef == null
                ? Optional.empty()
                : credentialRef.map(value -> requireNonBlank(value, "credentialRef"));
        evidence = evidence == null ? Optional.empty() : evidence.map(value -> requireNonBlank(value, "evidence"));
    }

    static BundleInstallGrantLifecycleReceipt absent(String reason) {
        return new BundleInstallGrantLifecycleReceipt(
                "REVOKED",
                reason,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("grant=absent"));
    }

    static BundleInstallGrantLifecycleReceipt fromRecord(BundleInstallGrantRecord record) {
        return new BundleInstallGrantLifecycleReceipt(
                record.status(),
                record.reason(),
                Optional.of(record.grantFingerprint()),
                Optional.of(record.instanceId()),
                Optional.of(record.credentialRef()),
                Optional.of(record.evidence()));
    }

    boolean revokedOrAbsent() {
        return status.equals("REVOKED");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
