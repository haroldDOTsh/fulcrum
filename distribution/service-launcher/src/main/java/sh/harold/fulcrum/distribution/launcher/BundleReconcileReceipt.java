package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Instant;
import java.util.Optional;

record BundleReconcileReceipt(
        String bundleId,
        String status,
        String reason,
        String digest,
        Optional<String> grantFingerprint,
        Optional<String> artifactVerificationEvidence,
        Instant reconciledAt) {
    static BundleReconcileReceipt installed(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence evidence,
            Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                "INSTALLED",
                "declared-state-satisfied",
                bundle.digest(),
                Optional.of(grant.grantFingerprint()),
                Optional.of(evidence.wireValue()),
                now);
    }

    static BundleReconcileReceipt denied(DeclaredBundle bundle, String reason, Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                "DENIED",
                reason,
                bundle.digest(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt removed(String bundleId, Instant now) {
        return new BundleReconcileReceipt(
                bundleId,
                "REMOVED",
                "declaration-removed-grant-revoked",
                "none",
                Optional.empty(),
                Optional.empty(),
                now);
    }

    String toJson() {
        return "{"
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"reason\":\"" + escape(reason) + "\","
                + "\"digest\":\"" + escape(digest) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint.orElse("none")) + "\","
                + "\"artifactVerificationEvidence\":\"" + escape(artifactVerificationEvidence.orElse("none")) + "\","
                + "\"reconciledAt\":\"" + reconciledAt + "\""
                + "}";
    }

    static BundleReconcileReceipt fromJson(String json) {
        String grantFingerprint = field(json, "grantFingerprint");
        String artifactEvidence = field(json, "artifactVerificationEvidence");
        return new BundleReconcileReceipt(
                field(json, "bundleId"),
                field(json, "status"),
                field(json, "reason"),
                field(json, "digest"),
                grantFingerprint.equals("none") ? Optional.empty() : Optional.of(grantFingerprint),
                artifactEvidence.equals("none") ? Optional.empty() : Optional.of(artifactEvidence),
                Instant.parse(field(json, "reconciledAt")));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("receipt missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("receipt has unterminated field: " + name);
        }
        return unescape(json.substring(valueStart, end));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
