package sh.harold.fulcrum.api.data.transaction;

/**
 * Interface for storage backends that support transactions.
 */
public interface TransactionalBackend {
    
    /**
     * Begin a new transaction.
     * 
     * @param transactionId The unique identifier for this transaction
     */
    void beginTransaction(String transactionId);
    
    /**
     * Commit a transaction.
     * 
     * @param transactionId The transaction to commit
     */
    void commitTransaction(String transactionId);
    
    /**
     * Rollback a transaction.
     * 
     * @param transactionId The transaction to rollback
     */
    void rollbackTransaction(String transactionId);
    
    /**
     * Check if a transaction is active.
     * 
     * @param transactionId The transaction to check
     * @return true if the transaction is active
     */
    boolean isTransactionActive(String transactionId);
}