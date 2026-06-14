package sh.harold.fulcrum.api.data.impl.postgres;

import com.zaxxer.hikari.HikariConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Declared PostgreSQL pool budget for authority-plane services.
 */
public final class PostgresConnectionBudget {
    public static final String REGISTRY_SERVICE_BOUNDARY = "registry-service";
    public static final String GAME_NODE_BOUNDARY = "game-node";

    private PostgresConnectionBudget() {
    }

    public static Declaration declaration(String ownerRole,
                                          String moduleSource,
                                          String allowedRuntimeBoundary,
                                          String poolName,
                                          int declaredMaxPoolSize,
                                          int minimumIdle,
                                          long connectionTimeoutMillis) {
        return new Declaration(
            ownerRole,
            moduleSource,
            allowedRuntimeBoundary,
            poolName,
            declaredMaxPoolSize,
            minimumIdle,
            connectionTimeoutMillis
        );
    }

    public static Declaration fromHikariConfig(String ownerRole,
                                               String moduleSource,
                                               String allowedRuntimeBoundary,
                                               HikariConfig config) {
        Objects.requireNonNull(config, "config");
        return declaration(
            ownerRole,
            moduleSource,
            allowedRuntimeBoundary,
            config.getPoolName(),
            config.getMaximumPoolSize(),
            config.getMinimumIdle(),
            config.getConnectionTimeout()
        );
    }

    public static Declaration fromPoolProperties(String ownerRole,
                                                 String moduleSource,
                                                 String allowedRuntimeBoundary,
                                                 String poolName,
                                                 Properties properties,
                                                 int fallbackMaximumPoolSize,
                                                 int fallbackMinimumIdle,
                                                 long fallbackConnectionTimeoutMillis) {
        return declaration(
            ownerRole,
            moduleSource,
            allowedRuntimeBoundary,
            poolName,
            intProperty(properties, fallbackMaximumPoolSize, "maximumPoolSize", "maximum-pool-size"),
            intProperty(properties, fallbackMinimumIdle, "minimumIdle", "minimum-idle"),
            longProperty(properties, fallbackConnectionTimeoutMillis, "connectionTimeout", "connection-timeout")
        );
    }

    public static Report empty(int maxTotalPoolSize) {
        return inspect(List.of(), maxTotalPoolSize);
    }

    public static Report inspect(Collection<Declaration> declarations, int maxTotalPoolSize) {
        Objects.requireNonNull(declarations, "declarations");
        List<Declaration> sortedDeclarations = declarations.stream()
            .map(declaration -> Objects.requireNonNull(declaration, "declaration"))
            .sorted(Comparator
                .comparing(Declaration::poolName)
                .thenComparing(Declaration::ownerRole)
                .thenComparing(Declaration::moduleSource))
            .toList();

        int totalDeclaredMaxPoolSize = sortedDeclarations.stream()
            .mapToInt(Declaration::declaredMaxPoolSize)
            .sum();

        List<String> violations = new ArrayList<>();
        if (maxTotalPoolSize < 0) {
            violations.add("maxTotalPoolSize must be >= 0");
        } else if (totalDeclaredMaxPoolSize > maxTotalPoolSize) {
            violations.add(
                "declared Postgres max pool size " + totalDeclaredMaxPoolSize
                    + " exceeds allowed total " + maxTotalPoolSize
            );
        }

        for (Declaration declaration : sortedDeclarations) {
            if (GAME_NODE_BOUNDARY.equals(declaration.allowedRuntimeBoundary())
                && declaration.declaredMaxPoolSize() > 0) {
                violations.add(
                    "game-node boundary may not declare Postgres pool " + declaration.poolName()
                );
            }
        }

        return new Report(
            sortedDeclarations,
            totalDeclaredMaxPoolSize,
            maxTotalPoolSize,
            violations,
            fingerprint(sortedDeclarations, totalDeclaredMaxPoolSize, maxTotalPoolSize)
        );
    }

    private static int intProperty(Properties properties, int fallback, String... names) {
        String value = property(properties, names);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static long longProperty(Properties properties, long fallback, String... names) {
        String value = property(properties, names);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }

    private static String property(Properties properties, String... names) {
        if (properties == null) {
            return null;
        }
        for (String name : names) {
            String value = properties.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String fingerprint(List<Declaration> declarations,
                                      int totalDeclaredMaxPoolSize,
                                      int maxTotalPoolSize) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("total=" + totalDeclaredMaxPoolSize + ";max=" + maxTotalPoolSize + "\n")
                .getBytes(StandardCharsets.UTF_8));
            for (Declaration declaration : declarations) {
                digest.update(declaration.canonical().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            chars[index * 2] = alphabet[value >>> 4];
            chars[index * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(chars);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record Declaration(
        String ownerRole,
        String moduleSource,
        String allowedRuntimeBoundary,
        String poolName,
        int declaredMaxPoolSize,
        int minimumIdle,
        long connectionTimeoutMillis
    ) {
        public Declaration {
            ownerRole = requireNonBlank(ownerRole, "ownerRole");
            moduleSource = requireNonBlank(moduleSource, "moduleSource");
            allowedRuntimeBoundary = requireNonBlank(allowedRuntimeBoundary, "allowedRuntimeBoundary");
            poolName = requireNonBlank(poolName, "poolName");
            if (declaredMaxPoolSize < 0) {
                throw new IllegalArgumentException("declaredMaxPoolSize must be >= 0");
            }
            if (minimumIdle < 0) {
                throw new IllegalArgumentException("minimumIdle must be >= 0");
            }
            if (minimumIdle > declaredMaxPoolSize) {
                throw new IllegalArgumentException("minimumIdle must be <= declaredMaxPoolSize");
            }
            if (connectionTimeoutMillis <= 0) {
                throw new IllegalArgumentException("connectionTimeoutMillis must be > 0");
            }
        }

        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("poolName", poolName);
            payload.put("ownerRole", ownerRole);
            payload.put("moduleSource", moduleSource);
            payload.put("allowedRuntimeBoundary", allowedRuntimeBoundary);
            payload.put("declaredMaxPoolSize", declaredMaxPoolSize);
            payload.put("minimumIdle", minimumIdle);
            payload.put("connectionTimeoutMillis", connectionTimeoutMillis);
            return Collections.unmodifiableMap(payload);
        }

        String canonical() {
            return "poolName=" + poolName
                + ";ownerRole=" + ownerRole
                + ";moduleSource=" + moduleSource
                + ";allowedRuntimeBoundary=" + allowedRuntimeBoundary
                + ";declaredMaxPoolSize=" + declaredMaxPoolSize
                + ";minimumIdle=" + minimumIdle
                + ";connectionTimeoutMillis=" + connectionTimeoutMillis;
        }

        public String summary() {
            return canonical();
        }
    }

    public record Report(
        List<Declaration> declarations,
        int totalDeclaredMaxPoolSize,
        int maxTotalPoolSize,
        List<String> violations,
        String fingerprint
    ) {
        public Report {
            declarations = List.copyOf(declarations);
            violations = List.copyOf(violations);
            fingerprint = requireNonBlank(fingerprint, "fingerprint");
        }

        public boolean accepted() {
            return violations.isEmpty();
        }

        public void requireAccepted() {
            if (!accepted()) {
                throw new IllegalStateException("Postgres connection budget rejected: "
                    + String.join("; ", violations));
            }
        }

        public boolean declares(Declaration declaration) {
            return declarations.contains(Objects.requireNonNull(declaration, "declaration"));
        }

        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("accepted", accepted());
            payload.put("totalDeclaredMaxPoolSize", totalDeclaredMaxPoolSize);
            payload.put("maxTotalPoolSize", maxTotalPoolSize);
            payload.put("violations", violations);
            payload.put("declarations", declarations.stream()
                .map(Declaration::payload)
                .toList());
            return Collections.unmodifiableMap(payload);
        }

        public String summary() {
            String entries = declarations.stream()
                .map(Declaration::summary)
                .collect(Collectors.joining(", "));
            return "fingerprint=" + fingerprint
                + ", accepted=" + accepted()
                + ", totalDeclaredMaxPoolSize=" + totalDeclaredMaxPoolSize
                + ", maxTotalPoolSize=" + maxTotalPoolSize
                + ", declarations=[" + entries + "]"
                + (violations.isEmpty() ? "" : ", violations=" + violations);
        }
    }
}
