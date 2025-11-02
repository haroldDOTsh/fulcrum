package sh.harold.fulcrum.registry.proxy;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable identifier for Fulcrum proxies backed by the contiguous
 * {@code fulcrum-proxy-N} format allocated by the registry.
 */
public final class ProxyIdentifier implements Serializable, Comparable<ProxyIdentifier> {
    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "fulcrum-proxy-";
    private static final Pattern PERMANENT_ID_PATTERN = Pattern.compile("^fulcrum-proxy-(\\d+)$");

    private final int instanceNumber;
    private final String formattedId;

    private ProxyIdentifier(int instanceNumber, String formattedId) {
        this.instanceNumber = instanceNumber;
        this.formattedId = formattedId;
    }

    /**
     * Create a new proxy identifier for the given instance number.
     *
     * @param instanceNumber The contiguous proxy index (> 0)
     * @return a proxy identifier representing {@code fulcrum-proxy-instanceNumber}
     */
    public static ProxyIdentifier create(int instanceNumber) {
        validateInstanceNumber(instanceNumber);
        return new ProxyIdentifier(instanceNumber, PREFIX + instanceNumber);
    }

    /**
     * Parse a proxy identifier from its formatted string.
     *
     * @param idString The identifier string
     * @return the parsed proxy identifier
     * @throws IllegalArgumentException if the string does not match {@code fulcrum-proxy-N}
     */
    public static ProxyIdentifier parse(String idString) {
        if (idString == null || idString.isBlank()) {
            throw new IllegalArgumentException("Proxy ID string cannot be null or empty");
        }

        Matcher matcher = PERMANENT_ID_PATTERN.matcher(idString.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid proxy ID format: " + idString);
        }

        try {
            int instance = Integer.parseInt(matcher.group(1));
            validateInstanceNumber(instance);
            return new ProxyIdentifier(instance, PREFIX + instance);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric component in proxy ID: " + idString, ex);
        }
    }

    /**
     * Check if the supplied string is a valid proxy identifier.
     *
     * @param idString The string to validate
     * @return true if the string is a valid {@code fulcrum-proxy-N}
     */
    public static boolean isValid(String idString) {
        if (idString == null || idString.isBlank()) {
            return false;
        }

        Matcher matcher = PERMANENT_ID_PATTERN.matcher(idString.trim());
        if (!matcher.matches()) {
            return false;
        }

        try {
            int instance = Integer.parseInt(matcher.group(1));
            return instance > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static void validateInstanceNumber(int instanceNumber) {
        if (instanceNumber <= 0) {
            throw new IllegalArgumentException("Proxy instance number must be positive, got: " + instanceNumber);
        }
    }

    /**
     * Get the contiguous instance number.
     *
     * @return the proxy instance number
     */
    public int getInstanceId() {
        return instanceNumber;
    }

    /**
     * Get the formatted proxy identifier string.
     *
     * @return {@code fulcrum-proxy-instanceNumber}
     */
    public String getFormattedId() {
        return formattedId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProxyIdentifier that)) {
            return false;
        }
        return instanceNumber == that.instanceNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceNumber);
    }

    @Override
    public String toString() {
        return formattedId;
    }

    @Override
    public int compareTo(ProxyIdentifier other) {
        if (other == null) {
            return 1;
        }
        return Integer.compare(this.instanceNumber, other.instanceNumber);
    }
}
