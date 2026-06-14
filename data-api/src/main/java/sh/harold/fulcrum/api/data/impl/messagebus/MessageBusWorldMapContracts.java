package sh.harold.fulcrum.api.data.impl.messagebus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executable transport contract for world-map store request frames.
 */
public final class MessageBusWorldMapContracts {
    private static final int SCHEMA_VERSION = 1;
    private static final Map<Operation, TransportContract> CONTRACTS = contracts();
    private static final String FINGERPRINT = fingerprint(CONTRACTS);

    private MessageBusWorldMapContracts() {
    }

    public static int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public static String fingerprint() {
        return FINGERPRINT;
    }

    public static Map<Operation, TransportContract> all() {
        return CONTRACTS;
    }

    public static Map<String, Object> payload(Operation operation, Map<String, Object> values) {
        Objects.requireNonNull(operation, "operation");
        LinkedHashMap<String, Object> stamped = new LinkedHashMap<>();
        if (values != null) {
            stamped.putAll(values);
        }
        stamped.put("operation", operation.name());
        stamped.put("schemaVersion", SCHEMA_VERSION);
        stamped.put("contractFingerprint", FINGERPRINT);
        return Map.copyOf(stamped);
    }

    public static String rejection(Operation operation, Map<String, Object> payload) {
        Objects.requireNonNull(operation, "operation");
        TransportContract contract = contract(operation);
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;

        String actualFingerprint = string(safePayload.get("contractFingerprint"));
        if (!FINGERPRINT.equals(actualFingerprint)) {
            return "World map transport contract fingerprint mismatch: expected " + shortFingerprint(FINGERPRINT)
                + " but received " + shortFingerprint(actualFingerprint);
        }

        int schemaVersion;
        try {
            schemaVersion = intValue(safePayload.get("schemaVersion"), -1);
        } catch (RuntimeException exception) {
            return "World map transport schema version is invalid";
        }
        if (schemaVersion != SCHEMA_VERSION) {
            return "World map transport schema version " + schemaVersion
                + " is not supported by world map transport contract version " + SCHEMA_VERSION;
        }

        String actualOperation = string(safePayload.get("operation"));
        if (!operation.name().equals(actualOperation)) {
            return "World map transport operation mismatch: expected " + operation.name()
                + " but received " + (actualOperation == null ? "<missing>" : actualOperation);
        }

        for (String requiredField : contract.requiredFields()) {
            Object value = safePayload.get(requiredField);
            if (value == null || value.toString().isBlank()) {
                return "World map transport " + operation + " is missing required field " + requiredField;
            }
        }

        for (String field : safePayload.keySet()) {
            if (!contract.allowedFields().contains(field)) {
                return "World map transport " + operation + " field " + field + " is not in the transport contract";
            }
        }
        return null;
    }

    private static TransportContract contract(Operation operation) {
        TransportContract contract = CONTRACTS.get(operation);
        if (contract == null) {
            throw new IllegalArgumentException("No world map transport contract for " + operation);
        }
        return contract;
    }

    private static Map<Operation, TransportContract> contracts() {
        EnumMap<Operation, TransportContract> values = new EnumMap<>(Operation.class);
        values.put(Operation.LOAD, new TransportContract(
            Operation.LOAD,
            Set.of("operation", "schemaVersion", "contractFingerprint"),
            Set.of("operation", "schemaVersion", "contractFingerprint")
        ));
        values.put(Operation.SAVE, new TransportContract(
            Operation.SAVE,
            Set.of("operation", "schemaVersion", "contractFingerprint", "worldName", "schematicBase64"),
            Set.of(
                "operation", "schemaVersion", "contractFingerprint", "serverId",
                "worldName", "displayName", "metadataJson", "schematicBase64"
            )
        ));
        return Map.copyOf(values);
    }

    private static String fingerprint(Map<Operation, TransportContract> contracts) {
        StringBuilder material = new StringBuilder()
            .append("worldMapSchemaVersion=").append(SCHEMA_VERSION).append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.operation().name().compareTo(right.operation().name()))
            .forEach(contract -> material
                .append(contract.operation().name()).append('|')
                .append(String.join(",", contract.requiredFields().stream().sorted().toList())).append('|')
                .append(String.join(",", contract.allowedFields().stream().sorted().toList()))
                .append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint world map transport contracts", exception);
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    public enum Operation {
        LOAD,
        SAVE
    }

    public record TransportContract(
        Operation operation,
        Set<String> requiredFields,
        Set<String> allowedFields
    ) {
        public TransportContract {
            operation = Objects.requireNonNull(operation, "operation");
            requiredFields = requiredFields == null ? Set.of() : Set.copyOf(requiredFields);
            allowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
            if (!allowedFields.containsAll(requiredFields)) {
                throw new IllegalArgumentException(operation + " required fields must be allowed fields");
            }
        }
    }
}
