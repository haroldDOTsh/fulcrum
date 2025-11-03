package sh.harold.fulcrum.api.data.storage;

import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.DocumentPatch;
import sh.harold.fulcrum.api.data.query.Query;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backend storage interface for document operations.
 * Implementations handle the actual storage mechanics (MongoDB, JSON, etc.)
 */
public interface StorageBackend {

    /**
     * Retrieve a document from storage.
     *
     * @param collection The collection name
     * @param id         The document ID
     * @return CompletableFuture with the document
     */
    CompletableFuture<Document> getDocument(String collection, String id);

    /**
     * Save a document to storage.
     *
     * @param collection The collection name
     * @param id         The document ID
     * @param data       The document data
     * @return CompletableFuture indicating completion
     */
    CompletableFuture<Void> saveDocument(String collection, String id, Map<String, Object> data);

    /**
     * Apply a partial update to a document.
     *
     * @param collection The collection name
     * @param id         The document ID
     * @param patch      The patch describing the update
     * @return CompletableFuture indicating completion
     */
    CompletableFuture<Void> patchDocument(String collection, String id, DocumentPatch patch);

    /**
     * Delete a document from storage.
     *
     * @param collection The collection name
     * @param id         The document ID
     * @return CompletableFuture with deletion success
     */
    CompletableFuture<Boolean> deleteDocument(String collection, String id);

    /**
     * Query documents in a collection.
     *
     * @param collection The collection name
     * @param query      The query to execute
     * @return CompletableFuture with matching documents
     */
    CompletableFuture<List<Document>> query(String collection, Query query);

    /**
     * Count documents matching a query.
     *
     * @param collection The collection name
     * @param query      The query to count (null for all documents)
     * @return CompletableFuture with the count
     */
    CompletableFuture<Long> count(String collection, Query query);

    /**
     * Get all documents in a collection.
     *
     * @param collection The collection name
     * @return CompletableFuture with all documents
     */
    CompletableFuture<List<Document>> getAllDocuments(String collection);

    /**
     * Shutdown hook for releasing any resources held by the backend.
     */
    default void shutdown() {
        // no-op by default
    }
}
