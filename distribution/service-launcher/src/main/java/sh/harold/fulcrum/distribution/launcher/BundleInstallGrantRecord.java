package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;

record BundleInstallGrantRecord(
        String bundleId,
        String status,
        String reason,
        String grantFingerprint,
        String instanceId,
        String credentialRef,
        String evidence,
        Instant observedAt) {
    BundleInstallGrantRecord {
        bundleId = requireNonBlank(bundleId, "bundleId");
        status = requireNonBlank(status, "status");
        reason = requireNonBlank(reason, "reason");
        grantFingerprint = requireNonBlank(grantFingerprint, "grantFingerprint");
        instanceId = requireNonBlank(instanceId, "instanceId");
        credentialRef = requireNonBlank(credentialRef, "credentialRef");
        evidence = requireNonBlank(evidence, "evidence");
        observedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
    }

    static BundleInstallGrantRecord installed(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            String grantStatus,
            String reason,
            Instant now) {
        return new BundleInstallGrantRecord(
                bundle.id(),
                grantStatus,
                reason,
                grant.grantFingerprint(),
                grant.securityContext().identity().instanceId().value(),
                grant.securityContext().credentialRef(),
                "grant=" + grantStatus
                        + "|bundle=" + bundle.id()
                        + "|fingerprint=" + grant.grantFingerprint(),
                now);
    }

    BundleInstallGrantRecord revoked(String reason, Instant now) {
        return new BundleInstallGrantRecord(
                bundleId,
                "REVOKED",
                reason,
                grantFingerprint,
                instanceId,
                credentialRef,
                "grant=REVOKED|bundle=" + bundleId + "|fingerprint=" + grantFingerprint,
                now);
    }

    boolean active() {
        return status.equals("ACTIVE");
    }

    boolean revoked() {
        return status.equals("REVOKED");
    }

    String toJson() {
        return "{"
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"reason\":\"" + escape(reason) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint) + "\","
                + "\"instanceId\":\"" + escape(instanceId) + "\","
                + "\"credentialRef\":\"" + escape(credentialRef) + "\","
                + "\"evidence\":\"" + escape(evidence) + "\","
                + "\"observedAt\":\"" + observedAt + "\""
                + "}";
    }

    static BundleInstallGrantRecord fromJson(String json) {
        return new BundleInstallGrantRecord(
                field(json, "bundleId"),
                field(json, "status"),
                field(json, "reason"),
                field(json, "grantFingerprint"),
                field(json, "instanceId"),
                field(json, "credentialRef"),
                field(json, "evidence"),
                Instant.parse(field(json, "observedAt")));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("install grant record missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("install grant record has unterminated field: " + name);
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
