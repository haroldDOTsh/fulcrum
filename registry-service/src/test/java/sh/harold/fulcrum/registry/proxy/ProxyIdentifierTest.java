package sh.harold.fulcrum.registry.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ProxyIdentifier class
 */
public class ProxyIdentifierTest {

    @Test
    @DisplayName("Should create ProxyIdentifier with valid parameters")
    void testCreateWithValidParameters() {
        // Arrange
        int instanceId = 42;

        // Act
        ProxyIdentifier proxyId = ProxyIdentifier.create(instanceId);

        // Assert
        assertNotNull(proxyId);
        assertEquals(instanceId, proxyId.getInstanceId());
        assertTrue(proxyId.getTimestamp() > 0);
        assertNotNull(proxyId.getUuid());
        assertNotNull(proxyId.getFormattedId());
        assertTrue(proxyId.getFormattedId().startsWith("proxy-"));
    }

    @Test
    @DisplayName("Should parse valid proxy ID string")
    void testParseValidProxyId() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        int instanceId = 5;
        long timestamp = System.currentTimeMillis();
        String proxyIdString = String.format("proxy-%s-%d-%d", uuid, instanceId, timestamp);

        // Act
        ProxyIdentifier parsed = ProxyIdentifier.parse(proxyIdString);

        // Assert
        assertNotNull(parsed);
        assertEquals(uuid, parsed.getUuid());
        assertEquals(instanceId, parsed.getInstanceId());
        assertEquals(timestamp, parsed.getTimestamp());
        assertEquals(proxyIdString, parsed.getFormattedId());
    }

    @Test
    @DisplayName("Should validate proxy ID format")
    void testIsValid() {
        // Valid formats
        assertTrue(ProxyIdentifier.isValid("proxy-550e8400-e29b-41d4-a716-446655440000-0-1234567890"));
        assertTrue(ProxyIdentifier.isValid("proxy-550e8400-e29b-41d4-a716-446655440000-99-1234567890"));

        // Invalid formats
        assertFalse(ProxyIdentifier.isValid(null));
        assertFalse(ProxyIdentifier.isValid(""));
        assertFalse(ProxyIdentifier.isValid("invalid"));
        assertFalse(ProxyIdentifier.isValid("temp-proxy-123"));
        assertFalse(ProxyIdentifier.isValid("fulcrum-proxy-5"));
        assertFalse(ProxyIdentifier.isValid("proxy-invalid-uuid-0-123"));
        assertFalse(ProxyIdentifier.isValid("proxy-550e8400-e29b-41d4-a716-446655440000-100-123")); // instance > 99
    }

    @Test
    @DisplayName("Should convert legacy formats")
    void testFromLegacy() {
        // Test temp-proxy format
        ProxyIdentifier fromTemp = ProxyIdentifier.fromLegacy("temp-proxy-15");
        assertNotNull(fromTemp);
        assertEquals(15, fromTemp.getInstanceId());

        // Test fulcrum-proxy format
        ProxyIdentifier fromFulcrum = ProxyIdentifier.fromLegacy("fulcrum-proxy-7");
        assertNotNull(fromFulcrum);
        assertEquals(7, fromFulcrum.getInstanceId());

        // Test unknown legacy format (should default to instance 0)
        ProxyIdentifier fromUnknown = ProxyIdentifier.fromLegacy("some-other-format");
        assertNotNull(fromUnknown);
        assertEquals(0, fromUnknown.getInstanceId());

        // Test overflow handling (instance > 99 wraps)
        ProxyIdentifier fromOverflow = ProxyIdentifier.fromLegacy("fulcrum-proxy-150");
        assertNotNull(fromOverflow);
        assertEquals(50, fromOverflow.getInstanceId()); // 150 % 100 = 50
    }

    @Test
    @DisplayName("Should throw exception for invalid instance ID")
    void testInvalidInstanceId() {
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(-1));
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(100));
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(999));
    }

    @Test
    @DisplayName("Should throw exception for invalid timestamp")
    void testInvalidTimestamp() {
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(uuid, 0, -1, "1.0"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(uuid, 0, 0, "1.0"));
    }

    @Test
    @DisplayName("Should throw exception for null UUID")
    void testNullUuid() {
        assertThrows(NullPointerException.class, () -> ProxyIdentifier.create(null, 0, 123456, "1.0"));
    }

    @Test
    @DisplayName("Should create new instance with different instance ID")
    void testWithInstanceId() {
        // Arrange
        ProxyIdentifier original = ProxyIdentifier.create(10);

        // Act
        ProxyIdentifier modified = original.withInstanceId(20);

        // Assert
        assertNotEquals(original, modified);
        assertEquals(original.getUuid(), modified.getUuid());
        assertEquals(original.getTimestamp(), modified.getTimestamp());
        assertEquals(20, modified.getInstanceId());
    }

    @Test
    @DisplayName("Should create new instance with different timestamp")
    void testCreateWithCustomTimestamp() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        int instanceId = 10;
        long timestamp = 123456789;

        // Act
        ProxyIdentifier created = ProxyIdentifier.create(uuid, instanceId, timestamp, "1.0");

        // Assert
        assertNotNull(created);
        assertEquals(uuid, created.getUuid());
        assertEquals(instanceId, created.getInstanceId());
        assertEquals(timestamp, created.getTimestamp());
    }

    @Test
    @DisplayName("Should correctly implement equals and hashCode")
    void testEqualsAndHashCode() {
        // Create two identical ProxyIdentifiers
        UUID uuid = UUID.randomUUID();
        int instanceId = 5;
        long timestamp = 123456789;

        ProxyIdentifier id1 = ProxyIdentifier.create(uuid, instanceId, timestamp, "1.0");
        ProxyIdentifier id2 = ProxyIdentifier.create(uuid, instanceId, timestamp, "1.0");
        ProxyIdentifier id3 = ProxyIdentifier.create(UUID.randomUUID(), instanceId, timestamp, "1.0");

        // Test equals
        assertEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertNotEquals(id1, null);
        assertNotEquals(id1, "not a ProxyIdentifier");

        // Test hashCode
        assertEquals(id1.hashCode(), id2.hashCode());
        assertNotEquals(id1.hashCode(), id3.hashCode());
    }

    @Test
    @DisplayName("Should correctly compare timestamps")
    void testCompareTimestamps() {
        // Arrange
        ProxyIdentifier older = ProxyIdentifier.create(UUID.randomUUID(), 1, 1000, "1.0");
        ProxyIdentifier newer = ProxyIdentifier.create(UUID.randomUUID(), 2, 2000, "1.0");

        // Act & Assert
        // Compare timestamps using the timestamp field
        assertTrue(newer.getTimestamp() > older.getTimestamp());
        assertFalse(older.getTimestamp() > newer.getTimestamp());
        assertEquals(older.getTimestamp(), older.getTimestamp());
    }

    @Test
    @DisplayName("Should correctly identify same instance")
    void testSameInstance() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        ProxyIdentifier id1 = ProxyIdentifier.create(uuid, 5, 1000, "1.0");
        ProxyIdentifier id2 = ProxyIdentifier.create(uuid, 5, 2000, "1.0"); // Same UUID and instance, different timestamp
        ProxyIdentifier id3 = ProxyIdentifier.create(uuid, 6, 1000, "1.0"); // Same UUID, different instance
        ProxyIdentifier id4 = ProxyIdentifier.create(UUID.randomUUID(), 5, 1000, "1.0"); // Different UUID, same instance

        // Act & Assert - check if they have same UUID and instance ID
        assertTrue(id1.getUuid().equals(id2.getUuid()) && id1.getInstanceId() == id2.getInstanceId());
        assertFalse(id1.getUuid().equals(id3.getUuid()) && id1.getInstanceId() == id3.getInstanceId());
        assertFalse(id1.getUuid().equals(id4.getUuid()) && id1.getInstanceId() == id4.getInstanceId());
    }

    @Test
    @DisplayName("Should handle case-insensitive parsing")
    void testCaseInsensitiveParsing() {
        // Arrange
        UUID uuid = UUID.randomUUID();
        String upperCase = String.format("PROXY-%s-5-123456", uuid.toString().toUpperCase());
        String lowerCase = String.format("proxy-%s-5-123456", uuid.toString().toLowerCase());
        String mixedCase = String.format("ProXy-%s-5-123456", uuid.toString());

        // Act & Assert - all should parse successfully
        ProxyIdentifier fromUpper = ProxyIdentifier.parse(upperCase);
        ProxyIdentifier fromLower = ProxyIdentifier.parse(lowerCase);
        ProxyIdentifier fromMixed = ProxyIdentifier.parse(mixedCase);

        assertNotNull(fromUpper);
        assertNotNull(fromLower);
        assertNotNull(fromMixed);
        assertEquals(fromUpper, fromLower);
        assertEquals(fromUpper, fromMixed);
    }

    @Test
    @DisplayName("Should have proper toString representation")
    void testToString() {
        // Arrange
        ProxyIdentifier proxyId = ProxyIdentifier.create(42);

        // Act
        String toString = proxyId.toString();

        // Assert
        assertNotNull(toString);
        assertEquals(proxyId.getFormattedId(), toString);
        assertTrue(toString.matches("^proxy-[a-f0-9-]+-42-\\d+$"));
    }

    @Test
    @DisplayName("Should handle edge case instance IDs")
    void testEdgeCaseInstanceIds() {
        // Test minimum valid instance ID
        ProxyIdentifier minId = ProxyIdentifier.create(0);
        assertEquals(0, minId.getInstanceId());

        // Test maximum valid instance ID
        ProxyIdentifier maxId = ProxyIdentifier.create(99);
        assertEquals(99, maxId.getInstanceId());
    }
}