package sh.harold.fulcrum.api.data;

import sh.harold.fulcrum.api.data.query.Query;
import java.util.List;
import java.util.Map;

/**
 * Interface representing a collection of documents.
 * Provides methods for CRUD operations and querying.
 */
public interface Collection {
    
    /**
     * Select a document by its ID.
     * 
     * @param id The document ID
     * @return The document interface
     */
    Document select(String id);
    
    /**
     * Alias for select() - get a document by its ID.
     * 
     * @param id The document ID
     * @return The document interface
     */
    Document document(String id);
    
    /**
     * Create a new document with the given ID and data.
     * 
     * @param id The document ID
     * @param data The initial data for the document
     * @return The created document
     */
    Document create(String id, Map<String, Object> data);
    
    /**
     * Delete a document by its ID.
     * 
     * @param id The document ID
     * @return true if the document was deleted, false otherwise
     */
    boolean delete(String id);
    
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
     * Get all documents in the collection.
     * 
     * @return List of all documents
     */
    List<Document> all();
    
    /**
     * Count the total number of documents in the collection.
     * 
     * @return The document count
     */
    long count();
}