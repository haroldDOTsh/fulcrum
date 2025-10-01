package sh.harold.fulcrum.api.data;

import sh.harold.fulcrum.api.data.query.Query;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface representing a collection of documents.
 * Provides methods for CRUD operations and querying.
 */
public interface Collection {
    
    /**
     * Asynchronously select a document by its ID.
     * 
     * @param id The document ID
     * @return Future resolving to the document interface
     */
    CompletableFuture<Document> selectAsync(String id);
    
    /**
     * Select a document by its ID.
     * Blocks until the asynchronous operation completes.
     * 
     * @param id The document ID
     * @return The document interface
     */
    default Document select(String id) {
        return selectAsync(id).join();
    }
    
    /**
     * Alias for select() - get a document by its ID.
     * 
     * @param id The document ID
     * @return The document interface
     */
    default Document document(String id) {
        return select(id);
    }
    
    /**
     * Asynchronously create a new document with the given ID and data.
     * 
     * @param id The document ID
     * @param data The initial data for the document
     * @return Future resolving to the created document
     */
    CompletableFuture<Document> createAsync(String id, Map<String, Object> data);
    
    /**
     * Create a new document with the given ID and data.
     * Blocks until the asynchronous operation completes.
     * 
     * @param id The document ID
     * @param data The initial data for the document
     * @return The created document
     */
    default Document create(String id, Map<String, Object> data) {
        return createAsync(id, data).join();
    }
    
    /**
     * Asynchronously delete a document by its ID.
     * 
     * @param id The document ID
     * @return Future resolving to true if the document was deleted, false otherwise
     */
    CompletableFuture<Boolean> deleteAsync(String id);
    
    /**
     * Delete a document by its ID.
     * Blocks until the asynchronous operation completes.
     * 
     * @param id The document ID
     * @return true if the document was deleted, false otherwise
     */
    default boolean delete(String id) {
        return deleteAsync(id).join();
    }
    
    /**
     * Start building a query for this collection.
     * 
     * @return A new Query builder
     */
    Query find();
    
    /**
     * Start building a query with an initial path condition.
     * 
     * @param path The path to query on
     * @return A new Query builder
     */
    Query where(String path);
    
    /**
     * Asynchronously get all documents in the collection.
     * 
     * @return Future resolving to a list of all documents
     */
    CompletableFuture<List<Document>> allAsync();
    
    /**
     * Get all documents in the collection.
     * Blocks until the asynchronous operation completes.
     * 
     * @return List of all documents
     */
    default List<Document> all() {
        return allAsync().join();
    }
    
    /**
     * Asynchronously count the total number of documents in the collection.
     * 
     * @return Future resolving to the document count
     */
    CompletableFuture<Long> countAsync();
    
    /**
     * Count the total number of documents in the collection.
     * Blocks until the asynchronous operation completes.
     * 
     * @return The document count
     */
    default long count() {
        return countAsync().join();
    }
}
