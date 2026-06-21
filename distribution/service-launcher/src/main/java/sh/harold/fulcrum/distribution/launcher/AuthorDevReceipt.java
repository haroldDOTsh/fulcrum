package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;
import java.util.Optional;

record AuthorDevReceipt(
        String schema,
        String bundleId,
        String kind,
        String status,
        String reason,
        String artifactDigest,
        Optional<String> artifactPath,
        String reloadMode,
        long fencingEpoch,
        Instant reloadedAt) {
    static final String SCHEMA = "fulcrum.author-dev-reload/v1";

    static AuthorDevReceipt reloaded(
            String bundleId,
            String kind,
            String artifactDigest,
            String artifactPath,
            String reloadMode,
            long fencingEpoch,
            Instant now) {
        return new AuthorDevReceipt(
                SCHEMA,
                bundleId,
                kind,
                "RELOADED",
                "dev-loop-satisfied",
                artifactDigest,
                Optional.of(artifactPath),
                reloadMode,
                fencingEpoch,
                now);
    }

    static AuthorDevReceipt refused(String bundleId, String kind, String reason, Instant now) {
        return new AuthorDevReceipt(
                SCHEMA,
                bundleId,
                kind,
                "REFUSED",
                reason,
                "none",
                Optional.empty(),
                "none",
                0,
                now);
    }

    String toJson() {
        return "{"
                + "\"schema\":\"" + escape(schema) + "\","
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"kind\":\"" + escape(kind) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"reason\":\"" + escape(reason) + "\","
                + "\"artifactDigest\":\"" + escape(artifactDigest) + "\","
                + "\"artifactPath\":\"" + escape(artifactPath.orElse("none")) + "\","
                + "\"reloadMode\":\"" + escape(reloadMode) + "\","
                + "\"fencingEpoch\":\"" + fencingEpoch + "\","
                + "\"reloadedAt\":\"" + reloadedAt + "\""
                + "}";
    }

    static AuthorDevReceipt fromJson(String json) {
        String artifactPath = field(json, "artifactPath");
        return new AuthorDevReceipt(
                field(json, "schema"),
                field(json, "bundleId"),
                field(json, "kind"),
                field(json, "status"),
                field(json, "reason"),
                field(json, "artifactDigest"),
                artifactPath.equals("none") ? Optional.empty() : Optional.of(artifactPath),
                field(json, "reloadMode"),
                Long.parseLong(field(json, "fencingEpoch")),
                Instant.parse(field(json, "reloadedAt")));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("author dev receipt missing field: " + name);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("author dev receipt has unterminated field: " + name);
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
