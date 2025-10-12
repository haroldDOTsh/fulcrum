package sh.harold.fulcrum.registry.validation;

/**
 * Enumeration of validation error codes for the registration system.
 *
 * @author Harold
 * @since 1.0.0
 */
public enum ValidationErrorCode {

    // ProxyIdentifier validation errors
    INVALID_PROXY_ID_FORMAT("PROXY001", "Invalid proxy ID format"),
    DUPLICATE_PROXY_ID("PROXY002", "Duplicate proxy ID detected"),

    // Required field errors
    MISSING_REQUIRED_FIELD("FIELD001", "Required field is missing"),
    NULL_VALUE("FIELD002", "Field value cannot be null"),
    EMPTY_VALUE("FIELD003", "Field value cannot be empty"),

    // Port validation errors
    INVALID_PORT_RANGE("PORT001", "Port number out of valid range"),
    RESERVED_PORT("PORT002", "Port number is reserved"),

    // Version validation errors
    UNSUPPORTED_VERSION("VERSION001", "Version not supported"),
    VERSION_MISMATCH("VERSION002", "Version mismatch detected"),

    // Timestamp validation errors
    STALE_TIMESTAMP("TIME001", "Timestamp is too old"),
    FUTURE_TIMESTAMP("TIME002", "Timestamp is in the future"),

    // Capacity errors
    MAX_PROXIES_EXCEEDED("CAPACITY001", "Maximum number of proxies exceeded"),
    MAX_SERVERS_EXCEEDED("CAPACITY002", "Maximum number of servers exceeded"),

    // Address validation errors
    INVALID_ADDRESS_FORMAT("ADDRESS001", "Invalid address format"),
    UNRESOLVABLE_ADDRESS("ADDRESS002", "Address cannot be resolved"),
    BLACKLISTED_ADDRESS("ADDRESS003", "Address is blacklisted"),

    // State validation errors
    INVALID_STATE_TRANSITION("STATE001", "Invalid state transition"),
    INCONSISTENT_STATE("STATE002", "Inconsistent state detected"),

    // General validation errors
    VALIDATION_FAILED("GENERAL001", "Validation failed"),
    INTERNAL_ERROR("GENERAL002", "Internal validation error"),
    CONFIGURATION_ERROR("GENERAL003", "Validation configuration error");

    private final String code;
    private final String defaultMessage;

    ValidationErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * Find error code by its string code
     *
     * @param code The error code string
     * @return The matching ValidationErrorCode or null
     */
    public static ValidationErrorCode fromCode(String code) {
        for (ValidationErrorCode errorCode : values()) {
            if (errorCode.code.equals(code)) {
                return errorCode;
            }
        }
        return null;
    }

    /**
     * Get the error code string
     *
     * @return The error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Get the default error message
     *
     * @return The default message
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return code + ": " + defaultMessage;
    }
}