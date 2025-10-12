package sh.harold.fulcrum.api.data.transaction;

import sh.harold.fulcrum.api.data.Collection;

/**
 * Interface for database transactions.
 * Provides ACID guarantees for multiple operations.
 */
public interface Transaction {

    /**
     * Begin the transaction.
     *
     * @return This transaction for chaining
     */
    Transaction begin();

    /**
     * Commit the transaction.
     * All changes made within this transaction will be persisted.
     */
    void commit();

    /**
     * Rollback the transaction.
     * All changes made within this transaction will be discarded.
     */
    void rollback();

    /**
     * Create a savepoint in the transaction.
     *
     * @param name The name of the savepoint
     */
    void savepoint(String name);

    /**
     * Rollback to a specific savepoint.
     *
     * @param savepointName The name of the savepoint to rollback to
     */
    void rollbackTo(String savepointName);

    /**
     * Check if the transaction is active.
     *
     * @return true if the transaction is still active
     */
    boolean isActive();

    /**
     * Access a collection within the transaction.
     *
     * @param collection The collection name
     * @return The transactional collection interface
     */
    Collection from(String collection);

    /**
     * Get the current isolation level.
     *
     * @return The current isolation level
     */
    IsolationLevel getIsolationLevel();

    /**
     * Set the isolation level for this transaction.
     *
     * @param level The isolation level
     */
    void setIsolationLevel(IsolationLevel level);

    /**
     * Transaction isolation levels.
     */
    enum IsolationLevel {
        /**
         * Dirty reads allowed - can read uncommitted changes from other transactions.
         */
        READ_UNCOMMITTED,

        /**
         * No dirty reads - can only read committed changes.
         */
        READ_COMMITTED,

        /**
         * Repeatable read - ensures same query returns same results within transaction.
         */
        REPEATABLE_READ,

        /**
         * Serializable - highest isolation, transactions appear to execute serially.
         */
        SERIALIZABLE
    }
}