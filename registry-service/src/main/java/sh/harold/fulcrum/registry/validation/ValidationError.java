package sh.harold.fulcrum.registry.validation;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a validation error with code, message, and context.
 *
 * @author Harold
 * @since 1.0.0
 */
public class ValidationError implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ValidationErrorCode code;
    private final String message;
    private final String fieldName;
    private final Object invalidValue;
    private final Map<String, Object> context;

    /**
     * Private constructor for builder pattern
     */
    private ValidationError(Builder builder) {
        this.code = Objects.requireNonNull(builder.code, "Error code cannot be null");
        this.message = builder.message != null ? builder.message : code.getDefaultMessage();
        this.fieldName = builder.fieldName;
        this.invalidValue = builder.invalidValue;
        this.context = new HashMap<>(builder.context);
    }

    /**
     * Create a new error builder
     *
     * @param code The error code
     * @return A new builder instance
     */
    public static Builder of(ValidationErrorCode code) {
        return new Builder(code);
    }

    /**
     * Get the error code
     *
     * @return The validation error code
     */
    public ValidationErrorCode getCode() {
        return code;
    }

    /**
     * Get the error message
     *
     * @return The error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Get the field name that caused the error
     *
     * @return The field name or null if not field-specific
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Get the invalid value that caused the error
     *
     * @return The invalid value or null
     */
    public Object getInvalidValue() {
        return invalidValue;
    }

    /**
     * Get additional context information
     *
     * @return The context map
     */
    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }

    /**
     * Get a context value by key
     *
     * @param key The context key
     * @return The context value or null
     */
    public Object getContextValue(String key) {
        return context.get(key);
    }

    /**
     * Get a formatted error message including field name if available
     *
     * @return A formatted error message
     */
    public String getFormattedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(code.getCode()).append("] ");

        if (fieldName != null) {
            sb.append("Field '").append(fieldName).append("': ");
        }

        sb.append(message);

        if (invalidValue != null) {
            sb.append(" (value: ").append(invalidValue).append(")");
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValidationError)) return false;
        ValidationError that = (ValidationError) o;
        return code == that.code &&
                Objects.equals(message, that.message) &&
                Objects.equals(fieldName, that.fieldName) &&
                Objects.equals(invalidValue, that.invalidValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, fieldName, invalidValue);
    }

    @Override
    public String toString() {
        return getFormattedMessage();
    }

    /**
     * Builder for ValidationError
     */
    public static class Builder {
        private final ValidationErrorCode code;
        private final Map<String, Object> context = new HashMap<>();
        private String message;
        private String fieldName;
        private Object invalidValue;

        private Builder(ValidationErrorCode code) {
            this.code = code;
        }

        /**
         * Set a custom error message
         *
         * @param message The error message
         * @return This builder
         */
        public Builder withMessage(String message) {
            this.message = message;
            return this;
        }

        /**
         * Set the field name that caused the error
         *
         * @param fieldName The field name
         * @return This builder
         */
        public Builder forField(String fieldName) {
            this.fieldName = fieldName;
            return this;
        }

        /**
         * Set the invalid value
         *
         * @param value The invalid value
         * @return This builder
         */
        public Builder withInvalidValue(Object value) {
            this.invalidValue = value;
            return this;
        }

        /**
         * Add context information
         *
         * @param key   The context key
         * @param value The context value
         * @return This builder
         */
        public Builder withContext(String key, Object value) {
            this.context.put(key, value);
            return this;
        }

        /**
         * Add multiple context values
         *
         * @param context The context map
         * @return This builder
         */
        public Builder withContext(Map<String, Object> context) {
            if (context != null) {
                this.context.putAll(context);
            }
            return this;
        }

        /**
         * Build the ValidationError
         *
         * @return The built ValidationError
         */
        public ValidationError build() {
            return new ValidationError(this);
        }
    }
}