package sh.harold.fulcrum.registry.authority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guards target-mode authority startup switches that would otherwise bind traffic without advancing the log path.
 */
public final class AuthorityRuntimeAdmission {
    private AuthorityRuntimeAdmission() {
    }

    public static Report inspect(Map<String, Object> authorityConfig) {
        Map<String, Object> commandWorker = stringObjectMap(valueMap(authorityConfig, "command-worker"));
        Map<String, Object> stateProjectionWorker =
            stringObjectMap(valueMap(authorityConfig, "state-projection-worker"));
        Map<String, Object> idempotencyCache = stringObjectMap(valueMap(authorityConfig, "idempotency-cache"));
        Map<String, Object> snapshotCache = stringObjectMap(valueMap(authorityConfig, "snapshot-cache"));
        boolean commandWorkersEnabled = booleanValue(commandWorker.get("enabled"), true);
        boolean stateProjectionWorkersEnabled = booleanValue(stateProjectionWorker.get("enabled"), true);
        boolean idempotencyCacheEnabled = booleanValue(idempotencyCache.get("enabled"), true);
        boolean snapshotCacheEnabled = booleanValue(snapshotCache.get("enabled"), true);

        List<String> violations = new ArrayList<>();
        if (!commandWorkersEnabled) {
            violations.add("authority.command-worker.enabled must be true");
        }
        if (!stateProjectionWorkersEnabled) {
            violations.add("authority.state-projection-worker.enabled must be true");
        }
        if (!idempotencyCacheEnabled) {
            violations.add("authority.idempotency-cache.enabled must be true");
        }
        if (!snapshotCacheEnabled) {
            violations.add("authority.snapshot-cache.enabled must be true");
        }

        return new Report(
            commandWorkersEnabled,
            stateProjectionWorkersEnabled,
            idempotencyCacheEnabled,
            snapshotCacheEnabled,
            violations.isEmpty(),
            violations
        );
    }

    private static Map<?, ?> valueMap(Map<String, Object> values, String key) {
        if (values == null) {
            return Map.of();
        }
        Object raw = values.get(key);
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return Map.copyOf(values);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String raw = value.toString().trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw);
    }

    public record Report(
        boolean commandWorkersEnabled,
        boolean stateProjectionWorkersEnabled,
        boolean idempotencyCacheEnabled,
        boolean snapshotCacheEnabled,
        boolean accepted,
        List<String> violations
    ) {
        public Report {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public void requireAccepted() {
            if (!accepted) {
                throw new IllegalStateException(
                    "Authority runtime admission failed: " + String.join("; ", violations)
                );
            }
        }

        public String summary() {
            return "commandWorkersEnabled=" + commandWorkersEnabled
                + ", stateProjectionWorkersEnabled=" + stateProjectionWorkersEnabled
                + ", idempotencyCacheEnabled=" + idempotencyCacheEnabled
                + ", snapshotCacheEnabled=" + snapshotCacheEnabled
                + ", accepted=" + accepted;
        }
    }
}
