package sh.harold.fulcrum.registry.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable node snapshot read from the registry metadata store.
 *
 * @param nodeId stable node identifier
 * @param nodeType registry node type, such as BACKEND or PROXY
 * @param address last advertised host address
 * @param port last advertised port
 * @param role routing role advertised by the node
 * @param state last persisted lifecycle state
 * @param capacity last advertised capacity
 * @param metadata serialized node-specific state
 * @param registeredAt original registration timestamp
 * @param updatedAt last snapshot timestamp
 * @param snapshotVersion attestation fingerprint version
 * @param snapshotId durable snapshot identity
 * @param snapshotSource service that wrote this snapshot
 * @param snapshotFingerprint stable fingerprint over restore-relevant payload
 */
public record RegistryNodeSnapshot(
    String nodeId,
    String nodeType,
    String address,
    int port,
    String role,
    String state,
    int capacity,
    Map<String, Object> metadata,
    Instant registeredAt,
    Instant updatedAt,
    int snapshotVersion,
    UUID snapshotId,
    String snapshotSource,
    String snapshotFingerprint
) {
    public static final int SNAPSHOT_ATTESTATION_VERSION = 1;
    private static final String INVALID_ATTESTATION_FINGERPRINT =
        "0000000000000000000000000000000000000000000000000000000000000000";

    public RegistryNodeSnapshot {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        registeredAt = registeredAt == null ? Instant.EPOCH : registeredAt;
        updatedAt = updatedAt == null ? registeredAt : updatedAt;
        snapshotVersion = snapshotVersion <= 0 ? SNAPSHOT_ATTESTATION_VERSION : snapshotVersion;
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        snapshotSource = requiredText(snapshotSource, "snapshotSource");
        snapshotFingerprint = requiredText(snapshotFingerprint, "snapshotFingerprint");
    }

    public RegistryNodeSnapshot(String nodeId,
                                String nodeType,
                                String address,
                                int port,
                                String role,
                                String state,
                                int capacity,
                                Map<String, Object> metadata,
                                Instant registeredAt,
                                Instant updatedAt) {
        this(
            nodeId,
            nodeType,
            address,
            port,
            role,
            state,
            capacity,
            metadata,
            registeredAt,
            updatedAt,
            SNAPSHOT_ATTESTATION_VERSION,
            UUID.randomUUID(),
            "registry-service",
            INVALID_ATTESTATION_FINGERPRINT
        );
    }

    /**
     * @return true when this snapshot represents a backend server.
     */
    public boolean isBackend() {
        return "BACKEND".equalsIgnoreCase(nodeType);
    }

    /**
     * @return true when this snapshot represents a proxy.
     */
    public boolean isProxy() {
        return "PROXY".equalsIgnoreCase(nodeType);
    }

    /**
     * @return true when this snapshot carries attestation fields.
     */
    public boolean hasAttestation() {
        return snapshotId != null
            && !snapshotSource.isBlank()
            && !snapshotFingerprint.isBlank();
    }

    /**
     * @return true when the persisted fingerprint still matches the restore payload.
     */
    public boolean hasValidAttestation() {
        return snapshotVersion == SNAPSHOT_ATTESTATION_VERSION
            && snapshotFingerprint.equals(expectedFingerprint());
    }

    /**
     * Snapshot restore fails closed unless the attestation matches the payload.
     */
    public boolean permitsRestore() {
        return hasValidAttestation();
    }

    public RegistryNodeSnapshot withAttestation(UUID snapshotId, String snapshotSource) {
        UUID effectiveSnapshotId = snapshotId == null ? UUID.randomUUID() : snapshotId;
        String effectiveSnapshotSource = normalize(snapshotSource, "registry-service");
        String fingerprint = fingerprintFor(
            SNAPSHOT_ATTESTATION_VERSION,
            effectiveSnapshotId,
            effectiveSnapshotSource,
            nodeId,
            nodeType,
            address,
            port,
            role,
            state,
            capacity,
            metadata
        );
        return new RegistryNodeSnapshot(
            nodeId,
            nodeType,
            address,
            port,
            role,
            state,
            capacity,
            metadata,
            registeredAt,
            updatedAt,
            SNAPSHOT_ATTESTATION_VERSION,
            effectiveSnapshotId,
            effectiveSnapshotSource,
            fingerprint
        );
    }

    public String expectedFingerprint() {
        return fingerprintFor(
            snapshotVersion,
            snapshotId,
            snapshotSource,
            nodeId,
            nodeType,
            address,
            port,
            role,
            state,
            capacity,
            metadata
        );
    }

    public static String fingerprintFor(int snapshotVersion,
                                        UUID snapshotId,
                                        String snapshotSource,
                                        String nodeId,
                                        String nodeType,
                                        String address,
                                        int port,
                                        String role,
                                        String state,
                                        int capacity,
                                        Map<String, Object> metadata) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "registry-node-snapshot");
            update(digest, Integer.toString(snapshotVersion));
            update(digest, snapshotId == null ? "" : snapshotId.toString());
            update(digest, normalize(snapshotSource, "registry-service"));
            update(digest, normalize(nodeId, ""));
            update(digest, normalize(nodeType, ""));
            update(digest, normalize(address, ""));
            update(digest, Integer.toString(port));
            update(digest, normalize(role, ""));
            update(digest, normalize(state, ""));
            update(digest, Integer.toString(capacity));
            update(digest, canonical(metadata == null ? Map.of() : metadata));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .toList()) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(quoted(entry.getKey().toString())).append(':').append(canonical(entry.getValue()));
                first = false;
            }
            return builder.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(canonical(item));
                first = false;
            }
            return builder.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return quoted(value.toString());
    }

    private static String quoted(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                builder.append('\\');
            }
            builder.append(character);
        }
        return builder.append('"').toString();
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
