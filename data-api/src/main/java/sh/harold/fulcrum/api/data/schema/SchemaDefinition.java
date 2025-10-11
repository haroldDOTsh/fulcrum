package sh.harold.fulcrum.api.data.schema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable description of a schema definition.
 * Developers typically create instances via {@link #fromResource(String, ClassLoader, String)}.
 */
public final class SchemaDefinition {

    private final String id;
    private final String description;
    private final Supplier<String> sqlSupplier;
    private volatile String cachedSql;
    private volatile String cachedChecksum;

    private SchemaDefinition(String id, String description, Supplier<String> sqlSupplier) {
        this.id = Objects.requireNonNull(id, "id");
        this.description = description != null ? description : "";
        this.sqlSupplier = Objects.requireNonNull(sqlSupplier, "sqlSupplier");
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /**
     * Loads the SQL script backing this definition.
     */
    public String loadSql() {
        String sql = cachedSql;
        if (sql == null) {
            synchronized (this) {
                sql = cachedSql;
                if (sql == null) {
                    sql = sqlSupplier.get();
                    if (sql == null) {
                        throw new IllegalStateException("Schema definition '" + id + "' produced null SQL");
                    }
                    cachedSql = sql;
                }
            }
        }
        return sql;
    }

    /**
     * Computes a stable checksum of the SQL script so we can detect changes between releases.
     */
    public String checksum() {
        String checksum = cachedChecksum;
        if (checksum == null) {
            synchronized (this) {
                checksum = cachedChecksum;
                if (checksum == null) {
                    checksum = computeSha256(loadSql());
                    cachedChecksum = checksum;
                }
            }
        }
        return checksum;
    }

    public static SchemaDefinition fromResource(String id,
                                                String description,
                                                ClassLoader classLoader,
                                                String resourcePath) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(resourcePath, "resourcePath");
        return new SchemaDefinition(id, description, () -> {
            try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalArgumentException("Schema resource not found: " + resourcePath);
                }
                return readAll(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read schema resource " + resourcePath, ex);
            }
        });
    }

    public static SchemaDefinition fromPath(String id, String description, Path path) {
        Objects.requireNonNull(path, "path");
        return new SchemaDefinition(id, description, () -> {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read schema file " + path, ex);
            }
        });
    }

    private static String readAll(BufferedReader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String part = Integer.toHexString(b & 0xFF);
                if (part.length() == 1) {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
