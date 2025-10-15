package sh.harold.fulcrum.registry.proxy;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable identifier for proxy instances.
 *
 * <p>This class encapsulates all identification information for a proxy instance,
 * providing a standardized format and ensuring consistency across the codebase.
 * The identifier format is: {@code proxy-{uuid}-{instance}-{timestamp}}
 *
 * <p>Example: {@code proxy-123e4567-e89b-12d3-a456-426614174000-01-1704067200000}
 *
 * @author Harold
 * @since 1.0.0
 */
public final class ProxyIdentifier implements Serializable, Comparable<ProxyIdentifier> {
    private static final long serialVersionUID = 1L;

    /**
     * Pattern for validating and parsing proxy ID strings.
     * Format: proxy-{uuid}-{instance}-{timestamp}
     */
    private static final Pattern ID_PATTERN = Pattern.compile(
            "^proxy-([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})-([0-9]{1,2})-(\\d+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final String ID_FORMAT = "proxy-%s-%d-%d";
    private static final String DEFAULT_VERSION = "1.0.0";

    private final UUID uuid;
    private final int instanceId;
    private final long timestamp;
    private final String version;
    private final String formattedId;

    /**
     * Private constructor to ensure immutability and validation.
     *
     * @param uuid       The unique identifier for the proxy
     * @param instanceId The instance number (0-99)
     * @param timestamp  The creation timestamp
     * @param version    The proxy version
     */
    private ProxyIdentifier(UUID uuid, int instanceId, long timestamp, String version) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.instanceId = instanceId;
        this.timestamp = timestamp;
        this.version = version != null ? version : DEFAULT_VERSION;
        this.formattedId = String.format(ID_FORMAT, uuid, instanceId, timestamp);

        validateInstanceId(instanceId);
        validateTimestamp(timestamp);
    }

    /**
     * Creates a new ProxyIdentifier with a generated UUID and current timestamp.
     *
     * @param instanceId The instance number (0-99)
     * @return A new ProxyIdentifier instance
     * @throws IllegalArgumentException if instanceId is invalid
     */
    public static ProxyIdentifier create(int instanceId) {
        return create(UUID.randomUUID(), instanceId);
    }

    /**
     * Creates a new ProxyIdentifier with the specified UUID and current timestamp.
     *
     * @param uuid       The unique identifier for the proxy
     * @param instanceId The instance number (0-99)
     * @return A new ProxyIdentifier instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static ProxyIdentifier create(UUID uuid, int instanceId) {
        return new ProxyIdentifier(uuid, instanceId, Instant.now().toEpochMilli(), DEFAULT_VERSION);
    }

    /**
     * Creates a new ProxyIdentifier with all specified parameters.
     *
     * @param uuid       The unique identifier for the proxy
     * @param instanceId The instance number (0-99)
     * @param timestamp  The creation timestamp in milliseconds
     * @param version    The proxy version (optional, can be null)
     * @return A new ProxyIdentifier instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static ProxyIdentifier create(UUID uuid, int instanceId, long timestamp, String version) {
        return new ProxyIdentifier(uuid, instanceId, timestamp, version);
    }

    /**
     * Parses a ProxyIdentifier from its string representation.
     *
     * @param idString The string representation of the proxy ID
     * @return A ProxyIdentifier instance
     * @throws IllegalArgumentException if the string format is invalid
     */
    public static ProxyIdentifier parse(String idString) {
        if (idString == null || idString.trim().isEmpty()) {
            throw new IllegalArgumentException("Proxy ID string cannot be null or empty");
        }

        Matcher matcher = ID_PATTERN.matcher(idString.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid proxy ID format. Expected: proxy-{uuid}-{instance}-{timestamp}, got: " + idString
            );
        }

        try {
            UUID uuid = UUID.fromString(matcher.group(1));
            int instanceId = Integer.parseInt(matcher.group(2));
            long timestamp = Long.parseLong(matcher.group(3));

            return new ProxyIdentifier(uuid, instanceId, timestamp, DEFAULT_VERSION);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse proxy ID components: " + idString, e);
        }
    }

    /**
     * Attempts to parse a ProxyIdentifier from its string representation.
     *
     * @param idString The string representation of the proxy ID
     * @return A ProxyIdentifier instance, or null if parsing fails
     */
    public static ProxyIdentifier tryParse(String idString) {
        try {
            return parse(idString);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Validates if a string is a valid proxy ID format.
     *
     * @param idString The string to validate
     * @return true if the string is a valid proxy ID, false otherwise
     */
    public static boolean isValid(String idString) {
        if (idString == null || idString.trim().isEmpty()) {
            return false;
        }
        return ID_PATTERN.matcher(idString.trim()).matches();
    }

    /**
     * Creates a ProxyIdentifier for migration purposes from legacy string IDs.
     * This method attempts to extract meaningful data from various legacy formats.
     *
     * @param legacyId The legacy ID string
     * @return A ProxyIdentifier instance with best-effort extraction
     */
    public static ProxyIdentifier fromLegacy(String legacyId) {
        if (legacyId == null || legacyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Legacy ID cannot be null or empty");
        }

        // First try standard parsing
        if (isValid(legacyId)) {
            return parse(legacyId);
        }

        // Handle temp-proxy-{uuid} format
        if (legacyId.startsWith("temp-proxy-")) {
            String suffix = legacyId.substring("temp-proxy-".length());
            try {
                UUID uuid = UUID.fromString(suffix);
                return new ProxyIdentifier(uuid, 0, Instant.now().toEpochMilli(), DEFAULT_VERSION);
            } catch (IllegalArgumentException ignored) {
                try {
                    int instanceId = Integer.parseInt(suffix);
                    UUID uuid = UUID.nameUUIDFromBytes(legacyId.getBytes());
                    return new ProxyIdentifier(uuid, Math.abs(instanceId) % 100, Instant.now().toEpochMilli(), DEFAULT_VERSION);
                } catch (NumberFormatException ex) {
                    // Fall through to next attempt
                }
            }
        }

        // Handle fulcrum-proxy-{number} format
        if (legacyId.startsWith("fulcrum-proxy-")) {
            String numberPart = legacyId.substring("fulcrum-proxy-".length());
            try {
                int instanceId = Integer.parseInt(numberPart);
                // Generate a deterministic UUID from the legacy ID
                UUID uuid = UUID.nameUUIDFromBytes(legacyId.getBytes());
                return new ProxyIdentifier(uuid, instanceId % 100, Instant.now().toEpochMilli(), DEFAULT_VERSION);
            } catch (Exception e) {
                // Fall through to default
            }
        }

        // Default: generate deterministic UUID from legacy ID
        UUID uuid = UUID.nameUUIDFromBytes(legacyId.getBytes());
        return new ProxyIdentifier(uuid, 0, Instant.now().toEpochMilli(), DEFAULT_VERSION);
    }

    private static void validateInstanceId(int instanceId) {
        if (instanceId < 0 || instanceId > 99) {
            throw new IllegalArgumentException(
                    "Instance ID must be between 0 and 99 (inclusive), got: " + instanceId
            );
        }
    }

    private static void validateTimestamp(long timestamp) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException(
                    "Timestamp must be a positive epoch millis value, got: " + timestamp
            );
        }
        // Sanity check: timestamp should not be in the far future (more than 1 year ahead)
        long maxFutureTime = Instant.now().toEpochMilli() + (365L * 24 * 60 * 60 * 1000);
        if (timestamp > maxFutureTime) {
            throw new IllegalArgumentException(
                    "Timestamp appears to be too far in the future: " + timestamp
            );
        }
    }

    /**
     * Gets the UUID component of this identifier.
     *
     * @return The UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Gets the instance ID component of this identifier.
     *
     * @return The instance ID (0-99)
     */
    public int getInstanceId() {
        return instanceId;
    }

    /**
     * Gets the timestamp component of this identifier.
     *
     * @return The timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the version component of this identifier.
     *
     * @return The version string
     */
    public String getVersion() {
        return version;
    }

    /**
     * Gets the creation time as an Instant.
     *
     * @return The creation instant
     */
    public Instant getCreationTime() {
        return Instant.ofEpochMilli(timestamp);
    }

    /**
     * Gets the formatted string representation of this identifier.
     * Format: proxy-{uuid}-{instance}-{timestamp}
     *
     * @return The formatted ID string
     */
    public String getFormattedId() {
        return formattedId;
    }

    /**
     * Creates a new ProxyIdentifier with an updated instance ID.
     *
     * @param newInstanceId The new instance ID
     * @return A new ProxyIdentifier with the updated instance ID
     */
    public ProxyIdentifier withInstanceId(int newInstanceId) {
        return new ProxyIdentifier(uuid, newInstanceId, timestamp, version);
    }

    /**
     * Creates a new ProxyIdentifier with an updated version.
     *
     * @param newVersion The new version
     * @return A new ProxyIdentifier with the updated version
     */
    public ProxyIdentifier withVersion(String newVersion) {
        return new ProxyIdentifier(uuid, instanceId, timestamp, newVersion);
    }

    /**
     * Creates a new ProxyIdentifier with a refreshed timestamp.
     *
     * @return A new ProxyIdentifier with the current timestamp
     */
    public ProxyIdentifier refresh() {
        return new ProxyIdentifier(uuid, instanceId, Instant.now().toEpochMilli(), version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProxyIdentifier that)) return false;
        return instanceId == that.instanceId &&
                timestamp == that.timestamp &&
                uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, instanceId, timestamp);
    }

    @Override
    public String toString() {
        return formattedId;
    }

    /**
     * Returns a detailed string representation for debugging purposes.
     *
     * @return A detailed string representation
     */
    public String toDetailedString() {
        return String.format(
                "ProxyIdentifier{uuid=%s, instanceId=%d, timestamp=%d, version=%s, formatted=%s}",
                uuid, instanceId, timestamp, version, formattedId
        );
    }

    @Override
    public int compareTo(ProxyIdentifier other) {
        if (other == null) return 1;

        // First compare by timestamp (older first)
        int timestampCompare = Long.compare(this.timestamp, other.timestamp);
        if (timestampCompare != 0) return timestampCompare;

        // Then by UUID
        int uuidCompare = this.uuid.compareTo(other.uuid);
        if (uuidCompare != 0) return uuidCompare;

        // Finally by instance ID
        return Integer.compare(this.instanceId, other.instanceId);
    }
}
