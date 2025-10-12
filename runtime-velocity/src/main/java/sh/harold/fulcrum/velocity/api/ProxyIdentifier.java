package sh.harold.fulcrum.velocity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable identifier for proxy servers following the standardized format:
 * proxy-{uuid}-{instance}-{timestamp}
 * <p>
 * This class provides:
 * - Immutable design pattern for thread safety
 * - Validation of proxy ID formats
 * - Parsing and generation of proxy IDs
 * - Backward compatibility with legacy formats
 */
public final class ProxyIdentifier {
    private static final Pattern PROXY_ID_PATTERN = Pattern.compile(
            "^proxy-([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})-(\\d{1,2})-(\\d+)$"
    );

    private static final String PREFIX = "proxy";
    private static final int MAX_INSTANCE_ID = 99;

    private final UUID uuid;
    private final int instanceId;
    private final long timestamp;
    private final String formattedId;

    /**
     * Private constructor - use static factory methods
     */
    private ProxyIdentifier(UUID uuid, int instanceId, long timestamp) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.instanceId = validateInstanceId(instanceId);
        this.timestamp = validateTimestamp(timestamp);
        this.formattedId = String.format("%s-%s-%d-%d", PREFIX, uuid, instanceId, timestamp);
    }

    /**
     * Creates a new ProxyIdentifier with the current timestamp
     *
     * @param instanceId The instance ID (0-99)
     * @return A new ProxyIdentifier
     */
    public static ProxyIdentifier create(int instanceId) {
        return new ProxyIdentifier(UUID.randomUUID(), instanceId, Instant.now().toEpochMilli());
    }

    /**
     * Creates a new ProxyIdentifier with all parameters
     *
     * @param uuid       The UUID
     * @param instanceId The instance ID (0-99)
     * @param timestamp  The timestamp in milliseconds
     * @return A new ProxyIdentifier
     */
    public static ProxyIdentifier create(UUID uuid, int instanceId, long timestamp) {
        return new ProxyIdentifier(uuid, instanceId, timestamp);
    }

    /**
     * Parses a proxy ID string
     *
     * @param proxyId The proxy ID string to parse
     * @return The parsed ProxyIdentifier
     * @throws IllegalArgumentException if the format is invalid
     */
    public static ProxyIdentifier parse(String proxyId) {
        Objects.requireNonNull(proxyId, "Proxy ID cannot be null");

        Matcher matcher = PROXY_ID_PATTERN.matcher(proxyId.toLowerCase());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid proxy ID format: " + proxyId);
        }

        UUID uuid = UUID.fromString(matcher.group(1));
        int instanceId = Integer.parseInt(matcher.group(2));
        long timestamp = Long.parseLong(matcher.group(3));

        return new ProxyIdentifier(uuid, instanceId, timestamp);
    }

    /**
     * Checks if a string is a valid proxy ID
     *
     * @param proxyId The string to check
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String proxyId) {
        if (proxyId == null) {
            return false;
        }
        return PROXY_ID_PATTERN.matcher(proxyId.toLowerCase()).matches();
    }

    /**
     * Converts a legacy format proxy ID to the new format
     *
     * @param legacyId The legacy ID (e.g., "temp-proxy-123", "fulcrum-proxy-5")
     * @return A new ProxyIdentifier with preserved instance number if possible
     */
    public static ProxyIdentifier fromLegacy(String legacyId) {
        Objects.requireNonNull(legacyId, "Legacy ID cannot be null");

        // Extract instance number from legacy formats
        int instanceId = 0;

        if (legacyId.startsWith("temp-proxy-") || legacyId.startsWith("fulcrum-proxy-")) {
            String[] parts = legacyId.split("-");
            if (parts.length >= 3) {
                try {
                    instanceId = Integer.parseInt(parts[2]) % (MAX_INSTANCE_ID + 1);
                } catch (NumberFormatException ignored) {
                    // Use default 0
                }
            }
        }

        return create(instanceId);
    }

    private static int validateInstanceId(int instanceId) {
        if (instanceId < 0 || instanceId > MAX_INSTANCE_ID) {
            throw new IllegalArgumentException(
                    "Instance ID must be between 0 and " + MAX_INSTANCE_ID + ", got: " + instanceId
            );
        }
        return instanceId;
    }

    private static long validateTimestamp(long timestamp) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException("Timestamp must be positive, got: " + timestamp);
        }
        return timestamp;
    }

    // Getters
    public UUID getUuid() {
        return uuid;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedId() {
        return formattedId;
    }

    /**
     * Creates a new ProxyIdentifier with a different instance ID
     *
     * @param newInstanceId The new instance ID
     * @return A new ProxyIdentifier with the updated instance ID
     */
    public ProxyIdentifier withInstanceId(int newInstanceId) {
        return new ProxyIdentifier(uuid, newInstanceId, timestamp);
    }

    /**
     * Creates a new ProxyIdentifier with a different timestamp
     *
     * @param newTimestamp The new timestamp
     * @return A new ProxyIdentifier with the updated timestamp
     */
    public ProxyIdentifier withTimestamp(long newTimestamp) {
        return new ProxyIdentifier(uuid, instanceId, newTimestamp);
    }

    /**
     * Creates a new ProxyIdentifier with the current timestamp
     *
     * @return A new ProxyIdentifier with the current timestamp
     */
    public ProxyIdentifier withCurrentTimestamp() {
        return withTimestamp(Instant.now().toEpochMilli());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyIdentifier that = (ProxyIdentifier) o;
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
     * Checks if this proxy ID is newer than another
     *
     * @param other The other ProxyIdentifier to compare
     * @return true if this proxy ID has a later timestamp
     */
    public boolean isNewerThan(ProxyIdentifier other) {
        return this.timestamp > other.timestamp;
    }

    /**
     * Checks if this proxy ID is from the same proxy instance
     *
     * @param other The other ProxyIdentifier to compare
     * @return true if both have the same UUID and instance ID
     */
    public boolean isSameInstance(ProxyIdentifier other) {
        return this.uuid.equals(other.uuid) && this.instanceId == other.instanceId;
    }
}