package sh.harold.fulcrum.api.data.impl;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of the Collection interface.
 * Handles document operations for a specific collection.
 */
public class CollectionImpl implements Collection {
    
    private final String name;
    private final StorageBackend backend;
    
    public CollectionImpl(String name, StorageBackend backend) {
        this.name = name;
        this.backend = backend;
    }
    
    @Override
    public Document select(String id) {
        try {
            // Return document synchronously for interface compatibility
            // The document itself will handle async operations
            CompletableFuture<Document> future = backend.getDocument(name, id);
            Document doc = future.get();
            return doc != null ? doc : new DocumentImpl(name, id, null, backend);
        } catch (Exception e) {
            // Return empty document on error
            return new DocumentImpl(name, id, null, backend);
        }
    }
    
    @Override
    public Document document(String id) {
        return select(id);
    }
    
    @Override
    public Document create(String id, Map<String, Object> data) {
        try {
            backend.saveDocument(name, id, data).get();
            return new DocumentImpl(name, id, data, backend);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create document", e);
        }
    }
    
    @Override
    public boolean delete(String id) {
        try {
            return backend.deleteDocument(name, id).get();
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public Query find() {
        return new QueryImpl(name, backend);
    }
    
    @Override
    public Query where(String path) {
        return new QueryImpl(name, backend).where(path);
    }
    
    @Override
    public List<Document> all() {
        try {
            return backend.getAllDocuments(name).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get all documents", e);
        }
    }
    
    @Override
    public long count() {
        try {
            return backend.count(name, null).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to count documents", e);
        }
    }
}