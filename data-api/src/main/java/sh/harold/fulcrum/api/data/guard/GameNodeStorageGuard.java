package sh.harold.fulcrum.api.data.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Enforces the game-node side of the data-layer ownership model.
 */
public final class GameNodeStorageGuard {
    private static final List<String> FORBIDDEN_TOP_LEVEL_KEYS = List.of(
        "postgres",
        "postgresql",
        "mysql",
        "mariadb",
        "mongo",
        "mongodb",
        "cassandra"
    );

    private GameNodeStorageGuard() {
    }

    public static void requireNoStoreGameNode(NodeKind nodeKind, Map<String, Object> config) {
        List<Violation> violations = inspectNoStoreGameNode(nodeKind, config);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(message(nodeKind, violations));
        }
    }

    public static List<Violation> inspectNoStoreGameNode(NodeKind nodeKind, Map<String, Object> config) {
        Objects.requireNonNull(nodeKind, "nodeKind");
        if (config == null || config.isEmpty()) {
            return List.of();
        }

        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", config, flattened);

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            String path = entry.getKey();
            String lowerPath = path.toLowerCase(Locale.ROOT);
            Object value = entry.getValue();

            if (isForbiddenTopLevelPath(lowerPath)) {
                violations.add(new Violation(path, "game-node config must not include direct database store settings"));
            } else if (lowerPath.endsWith("jdbc-url") || lowerPath.endsWith("jdbcurl")) {
                violations.add(new Violation(path, "game-node config must not include JDBC connection settings"));
            } else if (looksLikeDatabaseConnection(value)) {
                violations.add(new Violation(path, "game-node config must not include database connection strings"));
            }
        }
        return List.copyOf(violations);
    }

    private static boolean isForbiddenTopLevelPath(String lowerPath) {
        String topLevel = lowerPath;
        int dot = lowerPath.indexOf('.');
        if (dot >= 0) {
            topLevel = lowerPath.substring(0, dot);
        }
        return FORBIDDEN_TOP_LEVEL_KEYS.contains(topLevel);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> flattened) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String path = prefix.isBlank() ? entry.getKey().toString() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            flattened.put(path, value);
            if (value instanceof Map<?, ?> nested) {
                flatten(path, nested, flattened);
            }
        }
    }

    private static boolean looksLikeDatabaseConnection(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?>) {
            return false;
        }
        String text = value.toString().toLowerCase(Locale.ROOT);
        return text.contains("jdbc:")
            || text.contains("mongodb://")
            || text.contains("mongodb+srv://")
            || text.contains("cassandra://");
    }

    private static String message(NodeKind nodeKind, List<Violation> violations) {
        StringBuilder builder = new StringBuilder();
        builder.append("P3 no-store violation for ")
            .append(nodeKind.displayName())
            .append(" game node. Game nodes must use remote authority clients and must not ship direct database credentials:");
        for (Violation violation : violations) {
            builder.append(System.lineSeparator())
                .append("- ")
                .append(violation.path())
                .append(": ")
                .append(violation.reason());
        }
        return builder.toString();
    }

    public enum NodeKind {
        PAPER("Paper"),
        VELOCITY("Velocity");

        private final String displayName;

        NodeKind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record Violation(String path, String reason) {
        public Violation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
