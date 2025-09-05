package sh.harold.fulcrum.registry.validation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the result of a validation operation.
 * Can contain multiple validation errors if validation fails.
 * 
 * @author Harold
 * @since 1.0.0
 */
public class ValidationResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final boolean success;
    private final List<ValidationError> errors;
    private final long validationTime;
    private final String validatedObject;
    
    /**
     * Private constructor for builder pattern
     */
    private ValidationResult(boolean success, List<ValidationError> errors, String validatedObject) {
        this.success = success;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.validationTime = System.currentTimeMillis();
        this.validatedObject = validatedObject;
    }
    
    /**
     * Create a successful validation result
     * @param validatedObject Description of what was validated
     * @return A successful validation result
     */
    public static ValidationResult success(String validatedObject) {
        return new ValidationResult(true, Collections.emptyList(), validatedObject);
    }
    
    /**
     * Create a failed validation result with a single error
     * @param error The validation error
     * @param validatedObject Description of what was validated
     * @return A failed validation result
     */
    public static ValidationResult failure(ValidationError error, String validatedObject) {
        return new ValidationResult(false, Collections.singletonList(error), validatedObject);
    }
    
    /**
     * Create a failed validation result with multiple errors
     * @param errors The list of validation errors
     * @param validatedObject Description of what was validated
     * @return A failed validation result
     */
    public static ValidationResult failure(List<ValidationError> errors, String validatedObject) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("Failed validation must have at least one error");
        }
        return new ValidationResult(false, errors, validatedObject);
    }
    
    /**
     * Check if validation was successful
     * @return true if validation passed, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Check if validation failed
     * @return true if validation failed, false otherwise
     */
    public boolean isFailure() {
        return !success;
    }
    
    /**
     * Get all validation errors
     * @return Immutable list of validation errors (empty if successful)
     */
    public List<ValidationError> getErrors() {
        return errors;
    }
    
    /**
     * Get the first validation error if any
     * @return The first error or null if successful
     */
    public ValidationError getFirstError() {
        return errors.isEmpty() ? null : errors.get(0);
    }
    
    /**
     * Check if a specific error code is present
     * @param errorCode The error code to check
     * @return true if the error code is present
     */
    public boolean hasError(ValidationErrorCode errorCode) {
        return errors.stream().anyMatch(e -> e.getCode() == errorCode);
    }
    
    /**
     * Get errors for a specific field
     * @param fieldName The field name
     * @return List of errors for the field
     */
    public List<ValidationError> getFieldErrors(String fieldName) {
        return errors.stream()
            .filter(e -> fieldName.equals(e.getFieldName()))
            .toList();
    }
    
    /**
     * Get the time when validation was performed
     * @return The validation timestamp
     */
    public long getValidationTime() {
        return validationTime;
    }
    
    /**
     * Get description of what was validated
     * @return The validated object description
     */
    public String getValidatedObject() {
        return validatedObject;
    }
    
    /**
     * Get a formatted error message containing all errors
     * @return A formatted string with all error messages
     */
    public String getFormattedErrors() {
        if (success) {
            return "Validation successful";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Validation failed with ").append(errors.size()).append(" error(s):\n");
        for (ValidationError error : errors) {
            sb.append("  - ").append(error.getFormattedMessage()).append("\n");
        }
        return sb.toString();
    }
    
    /**
     * Combine multiple validation results into one
     * @param results The validation results to combine
     * @return A combined validation result
     */
    public static ValidationResult combine(List<ValidationResult> results) {
        if (results == null || results.isEmpty()) {
            return success("Combined validation");
        }
        
        List<ValidationError> allErrors = new ArrayList<>();
        StringBuilder objectDesc = new StringBuilder("Combined validation of: ");
        boolean first = true;
        
        for (ValidationResult result : results) {
            if (!first) {
                objectDesc.append(", ");
            }
            objectDesc.append(result.validatedObject);
            first = false;
            
            if (result.isFailure()) {
                allErrors.addAll(result.errors);
            }
        }
        
        return allErrors.isEmpty() 
            ? success(objectDesc.toString())
            : failure(allErrors, objectDesc.toString());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValidationResult)) return false;
        ValidationResult that = (ValidationResult) o;
        return success == that.success &&
               validationTime == that.validationTime &&
               Objects.equals(errors, that.errors) &&
               Objects.equals(validatedObject, that.validatedObject);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(success, errors, validationTime, validatedObject);
    }
    
    @Override
    public String toString() {
        return String.format("ValidationResult{success=%s, errors=%d, object=%s}",
            success, errors.size(), validatedObject);
    }
}