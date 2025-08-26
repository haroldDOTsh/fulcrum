package sh.harold.fulcrum.api.data.transaction;

/**
 * Exception thrown when transaction operations fail.
 */
public class TransactionException extends RuntimeException {
    
    public TransactionException(String message) {
        super(message);
    }
    
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public TransactionException(Throwable cause) {
        super(cause);
    }
}