package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;
import java.util.Optional;

record BundleContributionRecord(
        String bundleId,
        String status,
        String digest,
        Optional<String> cachePath,
        Optional<String> loadEvidence,
        Optional<String> grantFingerprint,
        Instant observedAt) {
    BundleContributionRecord {
        bundleId = requireNonBlank(bundleId, "bundleId");
        status = requireNonBlank(status, "status");
        digest = requireNonBlank(digest, "digest");
        cachePath = cachePath == null ? Optional.empty() : cachePath.map(value -> requireNonBlank(value, "cachePath"));
        loadEvidence = loadEvidence == null
                ? Optional.empty()
                : loadEvidence.map(value -> requireNonBlank(value, "loadEvidence"));
        grantFingerprint = grantFingerprint == null
                ? Optional.empty()
                : grantFingerprint.map(value -> requireNonBlank(value, "grantFingerprint"));
        observedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
    }

    static BundleContributionRecord installed(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            BundleContributionInstallReceipt receipt,
            Instant now) {
        return new BundleContributionRecord(
                bundle.id(),
                receipt.status(),
                bundle.digest(),
                receipt.cachePath(),
                receipt.loadEvidence(),
                Optional.of(grant.grantFingerprint()),
                now);
    }

    BundleContributionRecord removed(Instant now) {
        return new BundleContributionRecord(
                bundleId,
                "REMOVED",
                digest,
                Optional.empty(),
                loadEvidence,
                grantFingerprint,
                now);
    }

    String toJson() {
        return "{"
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"digest\":\"" + escape(digest) + "\","
                + "\"cachePath\":\"" + escape(cachePath.orElse("none")) + "\","
                + "\"loadEvidence\":\"" + escape(loadEvidence.orElse("none")) + "\","
                + "\"grantFingerprint\":\"" + escape(grantFingerprint.orElse("none")) + "\","
                + "\"observedAt\":\"" + observedAt + "\""
                + "}";
    }

    static BundleContributionRecord fromJson(String json) {
        return new BundleContributionRecord(
                field(json, "bundleId"),
                field(json, "status"),
                field(json, "digest"),
                optionalField(json, "cachePath"),
                optionalField(json, "loadEvidence"),
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
            throw new IllegalArgumentException("contribution record missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("contribution record has unterminated field: " + name);
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
