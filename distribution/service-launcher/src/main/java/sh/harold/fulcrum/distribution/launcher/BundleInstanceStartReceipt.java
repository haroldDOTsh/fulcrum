package sh.harold.fulcrum.distribution.launcher;

import java.util.Optional;

record BundleInstanceStartReceipt(
        String status,
        String reason,
        Optional<String> instanceId,
        Optional<String> shapeFingerprint,
        Optional<String> manifestHash,
        Optional<String> manifestPath,
        Optional<String> launchNonce,
        Optional<String> runtimeEvidence,
        Optional<String> registrationReceiptId,
        Optional<String> registrationEvidence) {
    BundleInstanceStartReceipt {
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        instanceId = instanceId == null ? Optional.empty() : instanceId.map(value -> requireNonBlank(value, "instanceId"));
        shapeFingerprint = shapeFingerprint == null ? Optional.empty() : shapeFingerprint
                .map(value -> requireNonBlank(value, "shapeFingerprint"));
        manifestHash = manifestHash == null ? Optional.empty() : manifestHash
                .map(value -> requireNonBlank(value, "manifestHash"));
        manifestPath = manifestPath == null ? Optional.empty() : manifestPath
                .map(value -> requireNonBlank(value, "manifestPath"));
        launchNonce = launchNonce == null ? Optional.empty() : launchNonce
                .map(BundleLaunchNonces::require);
        runtimeEvidence = runtimeEvidence == null ? Optional.empty() : runtimeEvidence
                .map(value -> requireNonBlank(value, "runtimeEvidence"));
        registrationReceiptId = registrationReceiptId == null ? Optional.empty() : registrationReceiptId
                .map(value -> requireNonBlank(value, "registrationReceiptId"));
        registrationEvidence = registrationEvidence == null ? Optional.empty() : registrationEvidence
                .map(value -> requireNonBlank(value, "registrationEvidence"));
    }

    static BundleInstanceStartReceipt running(
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce,
            String runtimeEvidence,
            String registrationReceiptId,
            String registrationEvidence) {
        return running(
                "instance-started-and-self-registered",
                instanceId,
                shapeFingerprint,
                manifestHash,
                manifestPath,
                launchNonce,
                runtimeEvidence,
                registrationReceiptId,
                registrationEvidence);
    }

    static BundleInstanceStartReceipt running(
            String reason,
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce,
            String runtimeEvidence,
            String registrationReceiptId,
            String registrationEvidence) {
        return new BundleInstanceStartReceipt(
                "RUNNING",
                reason,
                Optional.of(instanceId),
                Optional.of(shapeFingerprint),
                Optional.of(manifestHash),
                Optional.of(manifestPath),
                Optional.of(launchNonce),
                Optional.of(runtimeEvidence),
                Optional.of(registrationReceiptId),
                Optional.of(registrationEvidence));
    }

    static BundleInstanceStartReceipt rendered(
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce) {
        return new BundleInstanceStartReceipt(
                "RENDERED",
                "instance-rendered-start-pending",
                Optional.of(instanceId),
                Optional.of(shapeFingerprint),
                Optional.of(manifestHash),
                Optional.of(manifestPath),
                Optional.of(launchNonce),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static BundleInstanceStartReceipt started(
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce,
            String runtimeEvidence) {
        return new BundleInstanceStartReceipt(
                "STARTED",
                "runtime-started-registration-pending",
                Optional.of(instanceId),
                Optional.of(shapeFingerprint),
                Optional.of(manifestHash),
                Optional.of(manifestPath),
                Optional.of(launchNonce),
                Optional.of(runtimeEvidence),
                Optional.empty(),
                Optional.empty());
    }

    static BundleInstanceStartReceipt startFailed(
            String reason,
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce,
            String runtimeEvidence) {
        return new BundleInstanceStartReceipt(
                "START_FAILED",
                reason,
                Optional.of(instanceId),
                Optional.of(shapeFingerprint),
                Optional.of(manifestHash),
                Optional.of(manifestPath),
                Optional.of(launchNonce),
                Optional.of(runtimeEvidence),
                Optional.empty(),
                Optional.empty());
    }

    static BundleInstanceStartReceipt refused(String reason) {
        return new BundleInstanceStartReceipt(
                "REFUSED",
                reason,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static BundleInstanceStartReceipt refused(
            String reason,
            String instanceId,
            String shapeFingerprint,
            String manifestHash,
            String manifestPath,
            String launchNonce) {
        return new BundleInstanceStartReceipt(
                "REFUSED",
                reason,
                Optional.of(instanceId),
                Optional.of(shapeFingerprint),
                Optional.of(manifestHash),
                Optional.of(manifestPath),
                Optional.of(launchNonce),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    boolean rendered() {
        return status.equals("RENDERED")
                && instanceId.isPresent()
                && shapeFingerprint.isPresent()
                && manifestHash.isPresent()
                && manifestPath.isPresent()
                && launchNonce.isPresent()
                && runtimeEvidence.isEmpty()
                && registrationReceiptId.isEmpty()
                && registrationEvidence.isEmpty();
    }

    boolean started() {
        return status.equals("STARTED")
                && instanceId.isPresent()
                && shapeFingerprint.isPresent()
                && manifestHash.isPresent()
                && manifestPath.isPresent()
                && launchNonce.isPresent()
                && runtimeEvidence.isPresent()
                && registrationReceiptId.isEmpty()
                && registrationEvidence.isEmpty();
    }

    boolean startFailed() {
        return status.equals("START_FAILED")
                && instanceId.isPresent()
                && shapeFingerprint.isPresent()
                && manifestHash.isPresent()
                && manifestPath.isPresent()
                && launchNonce.isPresent()
                && runtimeEvidence.isPresent()
                && registrationReceiptId.isEmpty()
                && registrationEvidence.isEmpty();
    }

    boolean running() {
        return status.equals("RUNNING")
                && instanceId.isPresent()
                && shapeFingerprint.isPresent()
                && manifestHash.isPresent()
                && manifestPath.isPresent()
                && launchNonce.isPresent()
                && runtimeEvidence.isPresent()
                && registrationReceiptId.isPresent()
                && registrationEvidence.isPresent();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
