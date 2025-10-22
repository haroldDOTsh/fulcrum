package sh.harold.fulcrum.api.data.impl.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.DocumentPatch;
import sh.harold.fulcrum.api.data.impl.DocumentImpl;
import sh.harold.fulcrum.api.data.impl.QueryImpl;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * JSON file-based implementation of StorageBackend.
 * Features:
 * - File-based document storage (each document as {id}.json)
 * - Thread-safe file operations with locking
 * - In-memory LRU cache for performance
 * - Atomic file operations (write to temp, then rename)
 * - Query support by loading files into memory
 */
public class JsonStorageBackend implements StorageBackend {

    // Default cache size
    private static final int DEFAULT_CACHE_SIZE = 1000;
    private final Path basePath;
    private final Gson gson;
    private final ExecutorService executor;
    private final Map<String, ReadWriteLock> collectionLocks;
    private final LRUCache<String, Map<String, Object>> cache;
    private final int cacheSize;
    private final boolean enableCache;

    public JsonStorageBackend(Path basePath) {
        this(basePath, DEFAULT_CACHE_SIZE, true);
    }

    public JsonStorageBackend(Path basePath, int cacheSize, boolean enableCache) {
        this.basePath = basePath;
        this.cacheSize = cacheSize;
        this.enableCache = enableCache;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.executor = ForkJoinPool.commonPool();
        this.collectionLocks = new ConcurrentHashMap<>();
        this.cache = enableCache ? new LRUCache<>(cacheSize) : null;

        // Ensure base directory exists
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory: " + basePath, e);
        }
    }

    @Override
    public CompletableFuture<Document> getDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            String cacheKey = getCacheKey(collection, id);

            // Check cache first
            if (enableCache) {
                Map<String, Object> cached = cache.get(cacheKey);
                if (cached != null) {
                    return new DocumentImpl(collection, id, new HashMap<>(cached), this);
                }
            }

            ReadWriteLock lock = getCollectionLock(collection);
            lock.readLock().lock();
            try {
                Path documentPath = getDocumentPath(collection, id);

                if (!Files.exists(documentPath)) {
                    return new DocumentImpl(collection, id, null, this);
                }

                String json = Files.readString(documentPath, StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = gson.fromJson(json, Map.class);

                // Update cache
                if (enableCache && data != null) {
                    cache.put(cacheKey, new HashMap<>(data));
                }

                return new DocumentImpl(collection, id, data, this);
            } catch (IOException | JsonSyntaxException e) {
                // Return empty document on error
                return new DocumentImpl(collection, id, null, this);
            } finally {
                lock.readLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> saveDocument(String collection, String id, Map<String, Object> data) {
        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = getCollectionLock(collection);
            lock.writeLock().lock();
            try {
                Path collectionPath = basePath.resolve(collection);
                Files.createDirectories(collectionPath);

                Path documentPath = getDocumentPath(collection, id);
                Path tempPath = documentPath.resolveSibling(id + ".tmp");

                // Write to temp file first (atomic operation)
                String json = gson.toJson(data);
                Files.writeString(tempPath, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

                // Atomic rename
                Files.move(tempPath, documentPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                // Update cache
                if (enableCache) {
                    String cacheKey = getCacheKey(collection, id);
                    cache.put(cacheKey, new HashMap<>(data));
                }

                // Update index
                updateIndex(collection, id, true);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save document: " + collection + "/" + id, e);
            } finally {
                lock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> patchDocument(String collection, String id, DocumentPatch patch) {
        if (patch == null || patch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = getCollectionLock(collection);
            lock.writeLock().lock();
            try {
                Path collectionPath = basePath.resolve(collection);
                Files.createDirectories(collectionPath);

                Path documentPath = getDocumentPath(collection, id);
                Map<String, Object> existingData = null;
                boolean existed = Files.exists(documentPath);

                if (existed) {
                    String json = Files.readString(documentPath, StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = gson.fromJson(json, Map.class);
                    if (parsed != null) {
                        existingData = parsed;
                    }
                }

                if (!existed && !patch.isUpsert()) {
                    return;
                }

                Map<String, Object> updated = existingData != null ? new HashMap<>(existingData) : new HashMap<>();
                patch.applyToMap(updated, !existed);

                Path tempPath = documentPath.resolveSibling(id + ".tmp");
                String json = gson.toJson(updated);
                Files.writeString(tempPath, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

                Files.move(tempPath, documentPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                if (enableCache) {
                    cache.put(getCacheKey(collection, id), new HashMap<>(updated));
                }

                updateIndex(collection, id, true);
            } catch (IOException e) {
                throw new RuntimeException("Failed to patch document: " + collection + "/" + id, e);
            } finally {
                lock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getCollectionLock(collection);
            lock.writeLock().lock();
            try {
                Path documentPath = getDocumentPath(collection, id);

                if (!Files.exists(documentPath)) {
                    return false;
                }

                Files.delete(documentPath);

                // Remove from cache
                if (enableCache) {
                    String cacheKey = getCacheKey(collection, id);
                    cache.remove(cacheKey);
                }

                // Update index
                updateIndex(collection, id, false);

                return true;
            } catch (IOException e) {
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Document>> query(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getCollectionLock(collection);
            lock.readLock().lock();
            try {
                Path collectionPath = basePath.resolve(collection);

                if (!Files.exists(collectionPath)) {
                    return new ArrayList<>();
                }

                // Load all documents in collection
                List<Document> documents = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(collectionPath, "*.json")) {
                    for (Path documentPath : stream) {
                        String fileName = documentPath.getFileName().toString();
                        String documentId = fileName.substring(0, fileName.length() - 5); // Remove .json

                        String cacheKey = getCacheKey(collection, documentId);
                        Map<String, Object> data = null;

                        // Check cache first
                        if (enableCache) {
                            data = cache.get(cacheKey);
                        }

                        // Load from file if not cached
                        if (data == null) {
                            try {
                                String json = Files.readString(documentPath, StandardCharsets.UTF_8);
                                @SuppressWarnings("unchecked")
                                Map<String, Object> fileData = gson.fromJson(json, Map.class);
                                data = fileData;

                                // Update cache
                                if (enableCache && data != null) {
                                    cache.put(cacheKey, new HashMap<>(data));
                                }
                            } catch (IOException | JsonSyntaxException e) {
                                // Skip corrupted files
                                continue;
                            }
                        }

                        if (data != null) {
                            documents.add(new DocumentImpl(collection, documentId, new HashMap<>(data), this));
                        }
                    }
                }

                // The QueryImpl will handle filtering, sorting, and pagination
                return documents;
            } catch (IOException e) {
                return new ArrayList<>();
            } finally {
                lock.readLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Long> count(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getCollectionLock(collection);
            lock.readLock().lock();
            try {
                Path collectionPath = basePath.resolve(collection);

                if (!Files.exists(collectionPath)) {
                    return 0L;
                }

                // Quick count using index file if no query
                if (query == null) {
                    Path indexPath = collectionPath.resolve(".index");
                    if (Files.exists(indexPath)) {
                        try {
                            List<String> ids = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
                            return (long) ids.size();
                        } catch (IOException e) {
                            // Fall back to directory listing
                        }
                    }

                    // Count JSON files in directory
                    try (Stream<Path> stream = Files.list(collectionPath)) {
                        return stream.filter(p -> p.toString().endsWith(".json")).count();
                    }
                }

                // For queries, we need to load and filter documents
                List<Document> documents = query(collection, query).get();

                if (query instanceof QueryImpl) {
                    // The QueryImpl would have already filtered the documents
                    return (long) documents.size();
                }

                return (long) documents.size();
            } catch (IOException | InterruptedException | ExecutionException e) {
                return 0L;
            } finally {
                lock.readLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Document>> getAllDocuments(String collection) {
        return query(collection, null);
    }

    // Helper methods

    private Path getDocumentPath(String collection, String id) {
        return basePath.resolve(collection).resolve(id + ".json");
    }

    private String getCacheKey(String collection, String id) {
        return collection + ":" + id;
    }

    private ReadWriteLock getCollectionLock(String collection) {
        return collectionLocks.computeIfAbsent(collection, k -> new ReentrantReadWriteLock());
    }

    private void updateIndex(String collection, String id, boolean add) {
        try {
            Path collectionPath = basePath.resolve(collection);
            Path indexPath = collectionPath.resolve(".index");

            Set<String> ids = new HashSet<>();

            // Read existing index
            if (Files.exists(indexPath)) {
                ids.addAll(Files.readAllLines(indexPath, StandardCharsets.UTF_8));
            }

            // Update index
            if (add) {
                ids.add(id);
            } else {
                ids.remove(id);
            }

            // Write updated index
            Files.write(indexPath, ids, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Index update failure is non-critical
        }
    }

    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        if (executor instanceof ExecutorService) {
            executor.shutdown();
        }
    }

    /**
     * Simple LRU cache implementation
     */
    private static final class LRUCache<K, V> {
        private final Map<K, V> cache;
        private final int capacity;

        private LRUCache(int capacity) {
            this.capacity = capacity;
            this.cache = Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > LRUCache.this.capacity;
                }
            });
        }

        public V get(K key) {
            return cache.get(key);
        }

        public void put(K key, V value) {
            cache.put(key, value);
        }

        public void remove(K key) {
            cache.remove(key);
        }

        public void clear() {
            cache.clear();
        }
    }
}
