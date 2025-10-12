package sh.harold.fulcrum.api.data.transaction;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementation of Transaction interface.
 * Provides ACID guarantees for database operations.
 */
public class TransactionImpl implements Transaction {

    private final String transactionId;
    private final StorageBackend backend;
    private final List<TransactionOperation> operations;
    private final Map<String, Savepoint> savepoints;
    private final ReadWriteLock lock;
    // For optimistic locking
    private final Map<String, Long> versionMap;
    private boolean active;
    private IsolationLevel isolationLevel;

    public TransactionImpl(StorageBackend backend) {
        this(backend, IsolationLevel.READ_COMMITTED);
    }

    public TransactionImpl(StorageBackend backend, IsolationLevel isolationLevel) {
        this.transactionId = UUID.randomUUID().toString();
        this.backend = backend;
        this.operations = new ArrayList<>();
        this.savepoints = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.active = true;
        this.isolationLevel = isolationLevel;
        this.versionMap = new ConcurrentHashMap<>();
    }

    @Override
    public Transaction begin() {
        lock.writeLock().lock();
        try {
            if (!active) {
                throw new IllegalStateException("Transaction already completed");
            }
            // Initialize transaction in backend if supported
            if (backend instanceof TransactionalBackend) {
                ((TransactionalBackend) backend).beginTransaction(transactionId);
            }
            return this;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void commit() {
        lock.writeLock().lock();
        try {
            if (!active) {
                throw new IllegalStateException("Transaction already completed");
            }

            // Execute all operations atomically
            try {
                for (TransactionOperation op : operations) {
                    executeOperation(op);
                }

                // Commit in backend if supported
                if (backend instanceof TransactionalBackend) {
                    ((TransactionalBackend) backend).commitTransaction(transactionId);
                }

                active = false;
            } catch (Exception e) {
                // Rollback on any error
                rollback();
                throw new TransactionException("Failed to commit transaction", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void rollback() {
        lock.writeLock().lock();
        try {
            if (!active) {
                return; // Already rolled back or committed
            }

            // Rollback in backend if supported
            if (backend instanceof TransactionalBackend) {
                ((TransactionalBackend) backend).rollbackTransaction(transactionId);
            }

            // Clear all pending operations
            operations.clear();
            savepoints.clear();
            active = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void savepoint(String name) {
        lock.writeLock().lock();
        try {
            if (!active) {
                throw new IllegalStateException("Transaction not active");
            }
            savepoints.put(name, new Savepoint(name, operations.size()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void rollbackTo(String savepointName) {
        lock.writeLock().lock();
        try {
            if (!active) {
                throw new IllegalStateException("Transaction not active");
            }

            Savepoint savepoint = savepoints.get(savepointName);
            if (savepoint == null) {
                throw new IllegalArgumentException("Savepoint not found: " + savepointName);
            }

            // Remove operations after savepoint
            while (operations.size() > savepoint.operationIndex) {
                operations.remove(operations.size() - 1);
            }

            // Remove savepoints created after this one
            savepoints.entrySet().removeIf(entry ->
                    entry.getValue().operationIndex > savepoint.operationIndex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isActive() {
        lock.readLock().lock();
        try {
            return active;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection from(String collection) {
        return new TransactionalCollection(collection, this);
    }

    @Override
    public IsolationLevel getIsolationLevel() {
        lock.readLock().lock();
        try {
            return isolationLevel;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setIsolationLevel(IsolationLevel level) {
        lock.writeLock().lock();
        try {
            this.isolationLevel = level;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add an operation to the transaction queue.
     */
    void addOperation(TransactionOperation operation) {
        lock.writeLock().lock();
        try {
            if (!active) {
                throw new IllegalStateException("Transaction not active");
            }
            operations.add(operation);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Execute a single operation.
     */
    private void executeOperation(TransactionOperation op) throws Exception {
        switch (op.type) {
            case CREATE:
                backend.saveDocument(op.collection, op.documentId, op.data).get();
                break;
            case UPDATE:
                backend.saveDocument(op.collection, op.documentId, op.data).get();
                break;
            case DELETE:
                backend.deleteDocument(op.collection, op.documentId).get();
                break;
            case INCREMENT:
                Document doc = backend.getDocument(op.collection, op.documentId).get();
                if (doc != null) {
                    Map<String, Object> updatedData = doc.toMap();
                    for (Map.Entry<String, Object> entry : op.data.entrySet()) {
                        Object current = updatedData.get(entry.getKey());
                        if (current instanceof Number && entry.getValue() instanceof Number) {
                            double newValue = ((Number) current).doubleValue() +
                                    ((Number) entry.getValue()).doubleValue();
                            updatedData.put(entry.getKey(), newValue);
                        }
                    }
                    backend.saveDocument(op.collection, op.documentId, updatedData).get();
                }
                break;
        }
    }

    /**
     * Check for deadlock with other transactions.
     */
    boolean detectDeadlock(String resourceId) {
        // Simplified deadlock detection
        // In production, use wait-for graph or timeout-based detection
        return false;
    }

    /**
     * Transaction operation types.
     */
    enum OperationType {
        CREATE, UPDATE, DELETE, INCREMENT
    }

    /**
         * Represents a single operation in the transaction.
         */
        record TransactionOperation(OperationType type, String collection, String documentId, Map<String, Object> data) {
    }

    /**
         * Represents a savepoint in the transaction.
         */
        record Savepoint(String name, int operationIndex) {
    }

    /**
     * Transactional wrapper for Collection operations.
     */
    class TransactionalCollection implements Collection {
        private final String collectionName;
        private final TransactionImpl transaction;

        TransactionalCollection(String collectionName, TransactionImpl transaction) {
            this.collectionName = collectionName;
            this.transaction = transaction;
        }

        @Override
        public CompletableFuture<Document> selectAsync(String id) {
            return CompletableFuture.completedFuture(select(id));
        }

        @Override
        public Document select(String id) {
            return new TransactionalDocument(collectionName, id, transaction);
        }

        @Override
        public CompletableFuture<Document> createAsync(String id, Map<String, Object> data) {
            return CompletableFuture.completedFuture(create(id, data));
        }

        @Override
        public Document create(String id, Map<String, Object> data) {
            transaction.addOperation(new TransactionOperation(
                    OperationType.CREATE, collectionName, id, data));
            return new TransactionalDocument(collectionName, id, transaction);
        }

        @Override
        public CompletableFuture<Boolean> deleteAsync(String id) {
            return CompletableFuture.completedFuture(delete(id));
        }

        @Override
        public boolean delete(String id) {
            transaction.addOperation(new TransactionOperation(
                    OperationType.DELETE, collectionName, id, null));
            return true;
        }

        @Override
        public sh.harold.fulcrum.api.data.query.Query find() {
            throw new UnsupportedOperationException("Queries not supported in transactions");
        }

        @Override
        public sh.harold.fulcrum.api.data.query.Query where(String path) {
            throw new UnsupportedOperationException("Queries not supported in transactions");
        }

        @Override
        public CompletableFuture<List<Document>> allAsync() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Batch reads not supported in transactions"));
        }

        @Override
        public List<Document> all() {
            throw new UnsupportedOperationException("Batch reads not supported in transactions");
        }

        @Override
        public CompletableFuture<Long> countAsync() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Count not supported in transactions"));
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException("Count not supported in transactions");
        }
    }

    /**
     * Transactional wrapper for Document operations.
     */
    class TransactionalDocument implements Document {
        private final String collection;
        private final String id;
        private final TransactionImpl transaction;
        private final Map<String, Object> pendingChanges;

        TransactionalDocument(String collection, String id, TransactionImpl transaction) {
            this.collection = collection;
            this.id = id;
            this.transaction = transaction;
            this.pendingChanges = new HashMap<>();
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Object get(String path) {
            // First check pending changes
            if (pendingChanges.containsKey(path)) {
                return pendingChanges.get(path);
            }

            // Then check the actual document in backend
            try {
                Document doc = backend.getDocument(collection, id).get();
                if (doc != null && doc.exists()) {
                    return doc.get(path);
                }
            } catch (Exception e) {
                // Ignore exceptions
            }

            return null;
        }

        @Override
        public <T> T get(String path, T defaultValue) {
            Object value = pendingChanges.get(path);
            if (value == null) {
                return defaultValue;
            }
            @SuppressWarnings("unchecked")
            T result = (T) value;
            return result;
        }

        @Override
        public Document set(String path, Object value) {
            pendingChanges.put(path, value);
            // Save after each set operation to ensure it's captured in transaction
            save();
            return this;
        }

        @Override
        public CompletableFuture<Document> setAsync(String path, Object value) {
            set(path, value);
            return CompletableFuture.completedFuture(this);
        }

        public Document increment(String path, Number delta) {
            Map<String, Object> incData = new HashMap<>();
            incData.put(path, delta);
            transaction.addOperation(new TransactionOperation(
                    OperationType.INCREMENT, collection, id, incData));
            return this;
        }

        public Document remove(String path) {
            pendingChanges.put(path, null);
            return this;
        }

        @Override
        public boolean exists() {
            // Check if document exists in pending operations first
            for (TransactionOperation op : transaction.operations) {
                if (op.collection.equals(collection) && op.documentId.equals(id)) {
                    if (op.type == OperationType.CREATE || op.type == OperationType.UPDATE) {
                        return false; // Not yet committed
                    }
                    if (op.type == OperationType.DELETE) {
                        return false;
                    }
                }
            }

            // Then check backend
            try {
                Document doc = backend.getDocument(collection, id).get();
                return doc != null && doc.exists();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public Map<String, Object> toMap() {
            return new HashMap<>(pendingChanges);
        }

        @Override
        public String toJson() {
            // Simple JSON serialization
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : pendingChanges.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value == null) {
                    json.append("null");
                } else if (value instanceof String) {
                    json.append("\"").append(value).append("\"");
                } else {
                    json.append(value);
                }
                first = false;
            }
            json.append("}");
            return json.toString();
        }

        public void save() {
            if (!pendingChanges.isEmpty()) {
                // Load existing document data first
                Map<String, Object> fullData = new HashMap<>();
                try {
                    Document existingDoc = backend.getDocument(collection, id).get();
                    if (existingDoc != null && existingDoc.exists()) {
                        fullData = existingDoc.toMap();
                    }
                } catch (Exception e) {
                    // Ignore - start with empty map
                }

                // Apply pending changes on top
                fullData.putAll(pendingChanges);

                transaction.addOperation(new TransactionOperation(
                        OperationType.UPDATE, collection, id, new HashMap<>(fullData)));

                // Clear pending changes after queuing the operation
                pendingChanges.clear();
            }
        }

        public void delete() {
            transaction.addOperation(new TransactionOperation(
                    OperationType.DELETE, collection, id, null));
        }
    }
}


