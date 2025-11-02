package sh.harold.fulcrum.velocity.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight representation of a proxy identifier. Proxies now use the contiguous
 * {@code fulcrum-proxy-N} format allocated by the registry, while temporary bootstrapping
 * IDs retain the {@code temp-proxy-*} or {@code dev-proxy-*} prefixes.
 */
public final class ProxyIdentifier {
    private static final Pattern PERMANENT_PATTERN = Pattern.compile("^fulcrum-proxy-(\\d+)$");

    private final String formattedId;
    private final Integer instanceNumber; // null for temporary/dev identifiers

    private ProxyIdentifier(String formattedId, Integer instanceNumber) {
        this.formattedId = formattedId;
        this.instanceNumber = instanceNumber;
    }

    /**
     * Create a permanent proxy identifier for the given instance number.
     */
    public static ProxyIdentifier create(int instanceNumber) {
        validateInstanceNumber(instanceNumber);
        return new ProxyIdentifier("fulcrum-proxy-" + instanceNumber, instanceNumber);
    }

    /**
     * Parse a permanent proxy identifier string.
     */
    public static ProxyIdentifier parse(String proxyId) {
        if (!isValid(proxyId)) {
            throw new IllegalArgumentException("Invalid proxy ID format: " + proxyId);
        }

        Matcher matcher = PERMANENT_PATTERN.matcher(proxyId.trim());
        matcher.matches(); // guaranteed because of isValid
        int numeric = Integer.parseInt(matcher.group(1));
        return new ProxyIdentifier("fulcrum-proxy-" + numeric, numeric);
    }

    /**
     * Create a proxy identifier from any supported string.
     * Accepts permanent {@code fulcrum-proxy-N}, {@code temp-proxy-*}, and {@code dev-proxy-*}.
     */
    public static ProxyIdentifier fromString(String proxyId) {
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalArgumentException("Proxy ID cannot be null or empty");
        }
        String trimmed = proxyId.trim();
        if (isValid(trimmed)) {
            return parse(trimmed);
        }
        if (isTemporary(trimmed)) {
            return new ProxyIdentifier(trimmed, null);
        }
        throw new IllegalArgumentException("Unsupported proxy ID format: " + trimmed);
    }

    /**
     * Determine if the supplied identifier is in the permanent {@code fulcrum-proxy-N} format.
     */
    public static boolean isValid(String proxyId) {
        if (proxyId == null || proxyId.isBlank()) {
            return false;
        }
        Matcher matcher = PERMANENT_PATTERN.matcher(proxyId.trim());
        if (!matcher.matches()) {
            return false;
        }
        try {
            int numeric = Integer.parseInt(matcher.group(1));
            return numeric > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Determine if the identifier represents a temporary or development proxy.
     */
    public static boolean isTemporary(String proxyId) {
        if (proxyId == null || proxyId.isBlank()) {
            return false;
        }
        String lower = proxyId.toLowerCase(Locale.ROOT);
        return lower.startsWith("temp-proxy-") || lower.startsWith("dev-proxy-");
    }

    private static void validateInstanceNumber(int instanceNumber) {
        if (instanceNumber <= 0) {
            throw new IllegalArgumentException("Proxy instance number must be positive, got: " + instanceNumber);
        }
    }

    public String getFormattedId() {
        return formattedId;
    }

    /**
     * Returns the contiguous instance number. Temporary identifiers default to {@code 0}.
     */
    public int getInstanceId() {
        return instanceNumber != null ? instanceNumber : 0;
    }

    public boolean isTemporary() {
        return instanceNumber == null;
    }

    public boolean isPermanent() {
        return instanceNumber != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProxyIdentifier that)) {
            return false;
        }
        return formattedId.equals(that.formattedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formattedId);
    }

    @Override
    public String toString() {
        return formattedId;
    }
}
