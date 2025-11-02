package sh.harold.fulcrum.registry.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyIdentifierTest {

    @Test
    void createProducesContiguousFormat() {
        ProxyIdentifier identifier = ProxyIdentifier.create(7);

        assertEquals(7, identifier.getInstanceId());
        assertEquals("fulcrum-proxy-7", identifier.getFormattedId());
    }

    @Test
    void parseRestoresNumericIdentity() {
        ProxyIdentifier parsed = ProxyIdentifier.parse("fulcrum-proxy-42");

        assertEquals(42, parsed.getInstanceId());
        assertEquals("fulcrum-proxy-42", parsed.getFormattedId());
    }

    @Test
    void isValidRecognisesFulcrumFormat() {
        assertTrue(ProxyIdentifier.isValid("fulcrum-proxy-1"));
        assertTrue(ProxyIdentifier.isValid("fulcrum-proxy-123"));

        assertFalse(ProxyIdentifier.isValid(null));
        assertFalse(ProxyIdentifier.isValid(""));
        assertFalse(ProxyIdentifier.isValid("temp-proxy-1"));
        assertFalse(ProxyIdentifier.isValid("proxy-123"));
        assertFalse(ProxyIdentifier.isValid("fulcrum-proxy-0"));
        assertFalse(ProxyIdentifier.isValid("fulcrum-proxy--5"));
    }

    @Test
    void createRejectsNonPositiveNumbers() {
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(0));
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.create(-5));
    }

    @Test
    void parseRejectsInvalidStrings() {
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.parse("proxy-1"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIdentifier.parse("fulcrum-proxy-zero"));
    }

    @Test
    void equalsAndHashCodeUseInstanceNumber() {
        ProxyIdentifier left = ProxyIdentifier.create(10);
        ProxyIdentifier match = ProxyIdentifier.parse("fulcrum-proxy-10");
        ProxyIdentifier different = ProxyIdentifier.create(11);

        assertEquals(left, match);
        assertEquals(left.hashCode(), match.hashCode());
        assertNotEquals(left, different);
    }

    @Test
    void compareToOrdersByNumericValue() {
        ProxyIdentifier low = ProxyIdentifier.create(5);
        ProxyIdentifier high = ProxyIdentifier.create(8);

        assertTrue(low.compareTo(high) < 0);
        assertTrue(high.compareTo(low) > 0);
        assertEquals(0, high.compareTo(ProxyIdentifier.parse("fulcrum-proxy-8")));
    }
}
