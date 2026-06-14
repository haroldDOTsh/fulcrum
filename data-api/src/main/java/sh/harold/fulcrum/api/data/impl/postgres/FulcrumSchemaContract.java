package sh.harold.fulcrum.api.data.impl.postgres;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Data-api owned schema contract for canonical Fulcrum data tables.
 */
public final class FulcrumSchemaContract {
    public static final String RESOURCE_PATH = "schema/fulcrum-schema-contract.properties";

    private final int version;
    private final Map<String, TableContract> tables;

    private FulcrumSchemaContract(int version, Map<String, TableContract> tables) {
        this.version = version;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public static FulcrumSchemaContract loadDefault() {
        try (InputStream stream = FulcrumSchemaContract.class.getClassLoader()
            .getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Schema contract resource not found: " + RESOURCE_PATH);
            }
            return load(stream);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load schema contract: " + RESOURCE_PATH, exception);
        }
    }

    public static FulcrumSchemaContract load(InputStream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            Properties properties = new Properties();
            properties.load(stream);
            int version = Integer.parseInt(required(properties, "schema.version"));
            Map<String, TableContract> tables = new LinkedHashMap<>();
            for (String tableName : csv(properties.getProperty("tables"))) {
                tables.put(tableName, tableContract(properties, tableName));
            }
            return new FulcrumSchemaContract(version, tables);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse schema contract", exception);
        }
    }

    public int version() {
        return version;
    }

    public Map<String, TableContract> tables() {
        return tables;
    }

    public Set<String> tableNames() {
        return tables.keySet();
    }

    public String fingerprint() {
        StringBuilder canonical = new StringBuilder()
            .append("schema.version=").append(version).append('\n');
        tables.values().stream()
            .sorted(Comparator.comparing(TableContract::tableName))
            .forEach(table -> canonical
                .append(table.tableName()).append('|')
                .append(table.ddlOwner()).append('|')
                .append(table.dataOwner()).append('|')
                .append(table.createdBy()).append('|')
                .append(String.join(",", table.readers().stream().sorted().toList())).append('|')
                .append(String.join(",", table.writers().stream().sorted().toList())).append('\n'));
        return sha256(canonical.toString());
    }

    public TableContract table(String tableName) {
        TableContract contract = tables.get(tableName);
        if (contract == null) {
            throw new IllegalArgumentException("Unknown schema contract table: " + tableName);
        }
        return contract;
    }

    public TableContract requireDataApiOwnedTable(String tableName, String serviceId) {
        return requireDataApiOwnedTable(tableName, serviceId, serviceId);
    }

    public TableContract requireDataApiOwnedTable(String tableName, String readerServiceId, String writerServiceId) {
        TableContract contract = table(tableName);
        if (!"data-api".equals(contract.ddlOwner())) {
            throw new IllegalStateException(
                tableName + " DDL must be owned by data-api, not " + contract.ddlOwner()
            );
        }
        if (readerServiceId != null && !readerServiceId.isBlank() && !contract.canRead(readerServiceId)) {
            throw new IllegalStateException(
                tableName + " schema contract must grant " + readerServiceId + " read access"
            );
        }
        if (writerServiceId != null && !writerServiceId.isBlank() && !contract.canWrite(writerServiceId)) {
            throw new IllegalStateException(
                tableName + " schema contract must grant " + writerServiceId + " write access"
            );
        }
        return contract;
    }

    private static TableContract tableContract(Properties properties, String tableName) {
        String prefix = "table." + tableName + ".";
        return new TableContract(
            tableName,
            required(properties, prefix + "ddl-owner"),
            required(properties, prefix + "data-owner"),
            required(properties, prefix + "created-by"),
            csv(properties.getProperty(prefix + "readers")),
            csv(properties.getProperty(prefix + "writers"))
        );
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing schema contract property: " + key);
        }
        return value.trim();
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return Set.copyOf(values);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint schema contract", exception);
        }
    }

    public record TableContract(
        String tableName,
        String ddlOwner,
        String dataOwner,
        String createdBy,
        Set<String> readers,
        Set<String> writers
    ) {
        public TableContract {
            tableName = requiredValue(tableName, "tableName");
            ddlOwner = requiredValue(ddlOwner, "ddlOwner");
            dataOwner = requiredValue(dataOwner, "dataOwner");
            createdBy = requiredValue(createdBy, "createdBy");
            readers = readers == null ? Set.of() : Set.copyOf(readers);
            writers = writers == null ? Set.of() : Set.copyOf(writers);
        }

        public boolean canRead(String serviceId) {
            return readers.contains(serviceId);
        }

        public boolean canWrite(String serviceId) {
            return writers.contains(serviceId);
        }

        private static String requiredValue(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }
}
