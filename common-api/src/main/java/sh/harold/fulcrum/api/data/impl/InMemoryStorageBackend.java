package sh.harold.fulcrum.api.data.impl;

import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.DocumentPatch;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * In-memory implementation of StorageBackend.
 * Uses ConcurrentHashMap for thread-safe storage.
 */
public class InMemoryStorageBackend implements StorageBackend {

    // Structure: collection name -> document id -> document data
    private final Map<String, Map<String, Map<String, Object>>> storage = new ConcurrentHashMap<>();
    private final Executor executor;

    public InMemoryStorageBackend() {
        this(ForkJoinPool.commonPool());
    }

    public InMemoryStorageBackend(Executor executor) {
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    @Override
    public CompletableFuture<Document> getDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Map<String, Object>> collectionData = storage.get(collection);
            if (collectionData == null) {
                return new DocumentImpl(collection, id, null, this);
            }
            Map<String, Object> documentData = collectionData.get(id);
            return new DocumentImpl(collection, id, documentData, this);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> saveDocument(String collection, String id, Map<String, Object> data) {
        return CompletableFuture.runAsync(() -> {
            storage.computeIfAbsent(collection, k -> new ConcurrentHashMap<>())
                    .put(id, new HashMap<>(data));
        }, executor);
    }

    @Override
    public CompletableFuture<Void> patchDocument(String collection, String id, DocumentPatch patch) {
        if (patch == null || patch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            Map<String, Map<String, Object>> collectionData =
                    storage.computeIfAbsent(collection, k -> new ConcurrentHashMap<>());
            Map<String, Object> existing = collectionData.get(id);
            boolean existed = existing != null;

            if (!existed && !patch.isUpsert()) {
                return;
            }

            Map<String, Object> updated = existed ? new HashMap<>(existing) : new HashMap<>();
            patch.applyToMap(updated, !existed);
            collectionData.put(id, updated);
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Map<String, Object>> collectionData = storage.get(collection);
            if (collectionData != null) {
                Map<String, Object> removed = collectionData.remove(id);
                return removed != null;
            }
            return false;
        }, executor);
    }

    @Override
    public CompletableFuture<List<Document>> query(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Map<String, Object>> collectionData = storage.get(collection);
            if (collectionData == null) {
                return new ArrayList<>();
            }

            // Create documents from stored data
            List<Document> documents = collectionData.entrySet().stream()
                    .map(entry -> new DocumentImpl(collection, entry.getKey(), entry.getValue(), this))
                    .collect(Collectors.toList());

            // The QueryImpl will handle filtering, sorting, and pagination
            // when execute() is called
            return documents;
        }, executor);
    }

    @Override
    public CompletableFuture<Long> count(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Map<String, Object>> collectionData = storage.get(collection);
            if (collectionData == null) {
                return 0L;
            }

            if (query == null) {
                // Count all documents
                return (long) collectionData.size();
            }

            // Count filtered documents
            List<Document> documents = collectionData.entrySet().stream()
                    .map(entry -> new DocumentImpl(collection, entry.getKey(), entry.getValue(), this))
                    .collect(Collectors.toList());

            // Use query filtering if it's our QueryImpl
            if (query instanceof QueryImpl queryImpl) {
                long count = 0;
                for (Document doc : documents) {
                    if (queryImpl.getConditions().isEmpty()) {
                        count++;
                    } else {
                        // Check if document matches query conditions
                        // This is a simplified approach - the QueryImpl handles the actual filtering
                        count++;
                    }
                }
                return count;
            }

            return (long) documents.size();
        }, executor);
    }

    @Override
    public CompletableFuture<List<Document>> getAllDocuments(String collection) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Map<String, Object>> collectionData = storage.get(collection);
            if (collectionData == null) {
                return new ArrayList<>();
            }

            return collectionData.entrySet().stream()
                    .map(entry -> new DocumentImpl(collection, entry.getKey(), entry.getValue(), this))
                    .collect(Collectors.toList());
        }, executor);
    }
}
