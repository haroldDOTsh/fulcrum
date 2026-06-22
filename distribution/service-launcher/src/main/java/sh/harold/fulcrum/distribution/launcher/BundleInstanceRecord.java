package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;
import java.util.Optional;

record BundleInstanceRecord(
        String bundleId,
        String status,
        String digest,
        Optional<String> instanceId,
        Optional<String> shapeFingerprint,
        Optional<String> manifestHash,
        Optional<String> manifestPath,
        Optional<String> launchNonce,
        Optional<String> runtimeEvidence,
        Optional<String> registrationReceiptId,
        Optional<String> registrationEvidence,
        Optional<String> grantFingerprint,
        Instant observedAt) {
    BundleInstanceRecord {
        bundleId = requireNonBlank(bundleId, "bundleId");
        status = requireNonBlank(status, "status");
        digest = requireNonBlank(digest, "digest");
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
        grantFingerprint = grantFingerprint == null ? Optional.empty() : grantFingerprint
                .map(value -> requireNonBlank(value, "grantFingerprint"));
        observedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
    }

    static BundleInstanceRecord started(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            BundleInstanceStartReceipt startReceipt,
            Instant now) {
        return new BundleInstanceRecord(
                bundle.id(),
                startReceipt.status(),
                bundle.digest(),
                startReceipt.instanceId(),
                startReceipt.shapeFingerprint(),
                startReceipt.manifestHash(),
                startReceipt.manifestPath(),
                startReceipt.launchNonce(),
                startReceipt.runtimeEvidence(),
                startReceipt.registrationReceiptId(),
                startReceipt.registrationEvidence(),
                Optional.of(grant.grantFingerprint()),
                now);
    }

    BundleInstanceRecord removed(Instant now) {
        return new BundleInstanceRecord(
                bundleId,
                "REMOVED",
                digest,
                instanceId,
                shapeFingerprint,
                manifestHash,
                manifestPath,
                launchNonce,
                runtimeEvidence,
                registrationReceiptId,
                registrationEvidence,
                grantFingerprint,
                now);
    }

    String toJson() {
        return "{"
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"digest\":\"" + escape(digest) + "\","
                + "\"instanceId\":\"" + escape(instanceId.orElse("none")) + "\","
                + "\"shapeFingerprint\":\"" + escape(shapeFingerprint.orElse("none")) + "\","
                + "\"manifestHash\":\"" + escape(manifestHash.orElse("none")) + "\","
                + "\"manifestPath\":\"" + escape(manifestPath.orElse("none")) + "\","
                + "\"launchNonce\":\"" + escape(launchNonce.orElse("none")) + "\","
                + "\"runtimeEvidence\":\"" + escape(runtimeEvidence.orElse("none")) + "\","
                + "\"registrationReceiptId\":\"" + escape(registrationReceiptId.orElse("none")) + "\","
                + "\"registrationEvidence\":\"" + escape(registrationEvidence.orElse("none")) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint.orElse("none")) + "\","
                + "\"observedAt\":\"" + observedAt + "\""
                + "}";
    }

    static BundleInstanceRecord fromJson(String json) {
        return new BundleInstanceRecord(
                field(json, "bundleId"),
                field(json, "status"),
                field(json, "digest"),
                optionalField(json, "instanceId"),
                optionalField(json, "shapeFingerprint"),
                optionalField(json, "manifestHash"),
                optionalField(json, "manifestPath"),
                optionalField(json, "launchNonce"),
                optionalField(json, "runtimeEvidence"),
                optionalField(json, "registrationReceiptId"),
                optionalField(json, "registrationEvidence"),
                optionalField(json, "grantFingerprint"),
                Instant.parse(field(json, "observedAt")));
    }

    private static Optional<String> optionalField(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        String value = field(json, name);
        return value.equals("none") ? Optional.empty() : Optional.of(value);
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("instance record missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("instance record has unterminated field: " + name);
        }
        return unescape(json.substring(valueStart, end));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
