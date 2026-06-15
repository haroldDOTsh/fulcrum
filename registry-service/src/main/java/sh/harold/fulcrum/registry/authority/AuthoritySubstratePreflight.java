package sh.harold.fulcrum.registry.authority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks whether the central authority is running on the target substrates.
 */
public final class AuthoritySubstratePreflight {
    private static final String MODE_TARGET = "target";
    private static final String TARGET_COMMAND_LOG = "kafka";
    private static final String TARGET_HOT_STATE = "cassandra";
    private static final String TARGET_HISTORY = "postgresql";
    private static final String TARGET_CACHE = "valkey";

    private AuthoritySubstratePreflight() {
    }

    public static Report inspect(Map<String, Object> authorityConfig) {
        return inspect(authorityConfig, ActualSubstrate.compatibility());
    }

    public static Report inspect(Map<String, Object> authorityConfig, ActualSubstrate actualSubstrate) {
        Map<String, Object> substrate = stringObjectMap(valueMap(authorityConfig, "substrate"));
        String mode = normalized(substrate.get("mode"), MODE_TARGET);
        String declaredCommandLog = normalized(substrate.get("command-log"), TARGET_COMMAND_LOG);
        String declaredHotState = normalized(substrate.get("hot-state"), TARGET_HOT_STATE);
        String declaredHistory = normalized(substrate.get("history"), TARGET_HISTORY);
        String declaredCache = normalized(substrate.get("cache"), TARGET_CACHE);
        ActualSubstrate actual = actualSubstrate == null ? ActualSubstrate.compatibility() : actualSubstrate;
        String commandLog = actual.commandLog();
        String hotState = actual.hotState();
        String history = actual.history();
        String cache = actual.cache();

        List<String> limitations = limitations(commandLog, hotState, history, cache);
        List<String> violations = violations(mode, limitations);
        return new Report(
            mode,
            declaredCommandLog,
            declaredHotState,
            declaredHistory,
            declaredCache,
            commandLog,
            hotState,
            history,
            cache,
            limitations.isEmpty(),
            violations.isEmpty(),
            limitations,
            violations
        );
    }

    private static List<String> violations(String mode, List<String> limitations) {
        List<String> violations = new ArrayList<>();
        if (!MODE_TARGET.equals(mode)) {
            violations.add("mode is " + mode + ", expected " + MODE_TARGET);
        }
        violations.addAll(limitations);
        return List.copyOf(violations);
    }

    private static List<String> limitations(String commandLog, String hotState, String history, String cache) {
        List<String> limitations = new ArrayList<>();
        requireTarget(limitations, "command-log", TARGET_COMMAND_LOG, commandLog);
        requireTarget(limitations, "hot-state", TARGET_HOT_STATE, hotState);
        requireTarget(limitations, "history", TARGET_HISTORY, history);
        requireTarget(limitations, "cache", TARGET_CACHE, cache);
        return List.copyOf(limitations);
    }

    private static void requireTarget(
        List<String> limitations,
        String field,
        String expected,
        String actual
    ) {
        if (!expected.equals(actual)) {
            limitations.add(field + " is " + actual + ", expected " + expected);
        }
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

    private static String normalized(Object value, String fallback) {
        return value == null || value.toString().isBlank()
            ? fallback
            : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    public record ActualSubstrate(
        String commandLog,
        String hotState,
        String history,
        String cache
    ) {
        public ActualSubstrate {
            commandLog = normalized(commandLog, "unknown");
            hotState = normalized(hotState, "unknown");
            history = normalized(history, "unknown");
            cache = normalized(cache, "unknown");
        }

        public static ActualSubstrate compatibility() {
            return new ActualSubstrate("in-memory", "in-memory", TARGET_HISTORY, TARGET_CACHE);
        }
    }

    public record Report(
        String mode,
        String declaredCommandLog,
        String declaredHotState,
        String declaredHistory,
        String declaredCache,
        String commandLog,
        String hotState,
        String history,
        String cache,
        boolean targetComplete,
        boolean accepted,
        List<String> limitations,
        List<String> violations
    ) {
        public Report {
            mode = normalized(mode, MODE_TARGET);
            declaredCommandLog = normalized(declaredCommandLog, "unknown");
            declaredHotState = normalized(declaredHotState, "unknown");
            declaredHistory = normalized(declaredHistory, "unknown");
            declaredCache = normalized(declaredCache, "unknown");
            commandLog = normalized(commandLog, "unknown");
            hotState = normalized(hotState, "unknown");
            history = normalized(history, "unknown");
            cache = normalized(cache, "unknown");
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public void requireAccepted() {
            if (!accepted) {
                throw new IllegalStateException(
                    "Authority substrate preflight failed: " + String.join("; ", violations)
                );
            }
        }

        public String summary() {
            return "mode=" + mode
                + ", declaredCommandLog=" + declaredCommandLog
                + ", declaredHotState=" + declaredHotState
                + ", declaredHistory=" + declaredHistory
                + ", declaredCache=" + declaredCache
                + ", commandLog=" + commandLog
                + ", hotState=" + hotState
                + ", history=" + history
                + ", cache=" + cache
                + ", targetComplete=" + targetComplete;
        }
    }
}
