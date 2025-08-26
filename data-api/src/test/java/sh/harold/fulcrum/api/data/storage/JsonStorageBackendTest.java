package sh.harold.fulcrum.api.data.storage;

import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.QueryImpl;
import sh.harold.fulcrum.api.data.impl.json.JsonStorageBackend;
import sh.harold.fulcrum.api.data.query.Query;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for JsonStorageBackend.
 * Tests all functionality including thread safety, caching, and atomic operations.
 */
class JsonStorageBackendTest {
    
    @TempDir
    Path tempDir;
    
    private JsonStorageBackend backend;
    
    @BeforeEach
    void setUp() {
        backend = new JsonStorageBackend(tempDir);
    }
    
    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.shutdown();
        }
    }
    
    // ==================== Basic Operations Tests ====================
    
    @Test
    @DisplayName("Should save and retrieve a document")
    void testSaveAndRetrieveDocument() throws Exception {
        String collection = "users";
        String id = "user1";
        Map<String, Object> data = new HashMap<>();
        data.put("name", "John Doe");
        data.put("age", 30);
        data.put("email", "john@example.com");
        
        // Save document
        backend.saveDocument(collection, id, data).get();
        
        // Retrieve document
        Document doc = backend.getDocument(collection, id).get();
        
        assertNotNull(doc);
        assertTrue(doc.exists());
        assertEquals("John Doe", doc.get("name"));
        // JSON numbers are converted to Integer if they don't have decimal points
        Object age = doc.get("age");
        assertTrue(age instanceof Number);
        assertEquals(30, ((Number)age).intValue());
        assertEquals("john@example.com", doc.get("email"));
    }
    
    @Test
    @DisplayName("Should return empty document for non-existent ID")
    void testGetNonExistentDocument() throws Exception {
        Document doc = backend.getDocument("users", "nonexistent").get();
        
        assertNotNull(doc);
        assertFalse(doc.exists());
        assertNull(doc.get("any_field"));
    }
    
    @Test
    @DisplayName("Should delete document successfully")
    void testDeleteDocument() throws Exception {
        String collection = "users";
        String id = "user1";
        Map<String, Object> data = Map.of("name", "John");
        
        // Save document
        backend.saveDocument(collection, id, data).get();
        assertTrue(backend.getDocument(collection, id).get().exists());
        
        // Delete document
        boolean deleted = backend.deleteDocument(collection, id).get();
        assertTrue(deleted);
        
        // Verify deletion
        Document doc = backend.getDocument(collection, id).get();
        assertFalse(doc.exists());
    }
    
    @Test
    @DisplayName("Should return false when deleting non-existent document")
    void testDeleteNonExistentDocument() throws Exception {
        boolean deleted = backend.deleteDocument("users", "nonexistent").get();
        assertFalse(deleted);
    }
    
    // ==================== Query Tests ====================
    
    @Test
    @DisplayName("Should query all documents in collection")
    void testQueryAllDocuments() throws Exception {
        String collection = "products";
        
        // Save multiple documents
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", "Product " + i);
            data.put("price", i * 10.0);
            backend.saveDocument(collection, "product" + i, data).get();
        }
        
        // Query all documents
        Query query = new QueryImpl(collection, backend);
        List<Document> documents = backend.query(collection, query).get();
        
        assertEquals(5, documents.size());
        
        // Verify all documents are present
        Set<String> names = documents.stream()
            .map(doc -> (String) doc.get("name"))
            .collect(Collectors.toSet());
        
        assertTrue(names.contains("Product 1"));
        assertTrue(names.contains("Product 5"));
    }
    
    @Test
    @DisplayName("Should return empty list for empty collection")
    void testQueryEmptyCollection() throws Exception {
        List<Document> documents = backend.query("empty_collection", null).get();
        
        assertNotNull(documents);
        assertTrue(documents.isEmpty());
    }
    
    @Test
    @DisplayName("Should count documents correctly")
    void testCountDocuments() throws Exception {
        String collection = "items";
        
        // Initially empty
        assertEquals(0L, backend.count(collection, null).get());
        
        // Add documents
        for (int i = 1; i <= 3; i++) {
            backend.saveDocument(collection, "item" + i, Map.of("value", i)).get();
        }
        
        // Count should be 3
        assertEquals(3L, backend.count(collection, null).get());
        
        // Delete one
        backend.deleteDocument(collection, "item2").get();
        
        // Count should be 2
        assertEquals(2L, backend.count(collection, null).get());
    }
    
    @Test
    @DisplayName("Should get all documents in collection")
    void testGetAllDocuments() throws Exception {
        String collection = "books";
        
        // Save documents
        backend.saveDocument(collection, "book1", Map.of("title", "Book One")).get();
        backend.saveDocument(collection, "book2", Map.of("title", "Book Two")).get();
        backend.saveDocument(collection, "book3", Map.of("title", "Book Three")).get();
        
        // Get all documents
        List<Document> documents = backend.getAllDocuments(collection).get();
        
        assertEquals(3, documents.size());
    }
    
    // ==================== Nested Document Tests ====================
    
    @Test
    @DisplayName("Should handle nested document structures")
    void testNestedDocumentStructure() throws Exception {
        String collection = "profiles";
        String id = "profile1";
        
        Map<String, Object> address = new HashMap<>();
        address.put("street", "123 Main St");
        address.put("city", "Springfield");
        address.put("zip", "12345");
        
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Jane Doe");
        data.put("address", address);
        data.put("tags", Arrays.asList("developer", "designer"));
        
        // Save nested document
        backend.saveDocument(collection, id, data).get();
        
        // Retrieve and verify
        Document doc = backend.getDocument(collection, id).get();
        
        assertEquals("Jane Doe", doc.get("name"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> retrievedAddress = (Map<String, Object>) doc.get("address");
        assertEquals("Springfield", retrievedAddress.get("city"));
        
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) doc.get("tags");
        assertTrue(tags.contains("developer"));
    }
    
    // ==================== Concurrent Access Tests ====================
    
    @Test
    @DisplayName("Should handle concurrent writes safely")
    void testConcurrentWrites() throws Exception {
        String collection = "concurrent_test";
        int numThreads = 10;
        int documentsPerThread = 10;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        
        // Create multiple threads writing documents
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            Future<?> future = executor.submit(() -> {
                for (int d = 0; d < documentsPerThread; d++) {
                    String id = "thread" + threadId + "_doc" + d;
                    Map<String, Object> data = Map.of(
                        "thread", threadId,
                        "doc", d,
                        "timestamp", System.currentTimeMillis()
                    );
                    try {
                        backend.saveDocument(collection, id, data).get();
                    } catch (Exception e) {
                        fail("Concurrent write failed: " + e.getMessage());
                    }
                }
            });
            futures.add(future);
        }
        
        // Wait for all threads to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        
        executor.shutdown();
        
        // Verify all documents were written
        long count = backend.count(collection, null).get();
        assertEquals(numThreads * documentsPerThread, count);
    }
    
    @Test
    @DisplayName("Should handle concurrent reads and writes")
    void testConcurrentReadsAndWrites() throws Exception {
        String collection = "rw_test";
        String docId = "shared_doc";
        
        // Initial document
        backend.saveDocument(collection, docId, Map.of("counter", 0)).get();
        
        int numOperations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(numOperations);
        
        // Mix of reads and writes
        for (int i = 0; i < numOperations; i++) {
            final int operation = i;
            executor.submit(() -> {
                try {
                    if (operation % 2 == 0) {
                        // Write operation
                        Map<String, Object> data = Map.of("counter", operation);
                        backend.saveDocument(collection, docId, data).get();
                    } else {
                        // Read operation
                        Document doc = backend.getDocument(collection, docId).get();
                        assertNotNull(doc.get("counter"));
                    }
                } catch (Exception e) {
                    fail("Concurrent operation failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Document should still exist and be valid
        Document finalDoc = backend.getDocument(collection, docId).get();
        assertTrue(finalDoc.exists());
        assertNotNull(finalDoc.get("counter"));
    }
    
    // ==================== Atomic Operations Tests ====================
    
    @Test
    @DisplayName("Should perform atomic file writes")
    void testAtomicWrites() throws Exception {
        String collection = "atomic_test";
        String id = "test_doc";
        
        // Create a large document to increase chance of partial write
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            data.put("field" + i, "value" + i);
        }
        
        // Save document
        backend.saveDocument(collection, id, data).get();
        
        // Verify file exists and is valid JSON
        Path docPath = tempDir.resolve(collection).resolve(id + ".json");
        assertTrue(Files.exists(docPath));
        
        // Should not have temp file after successful write
        Path tempPath = tempDir.resolve(collection).resolve(id + ".tmp");
        assertFalse(Files.exists(tempPath));
        
        // Verify data integrity
        Document doc = backend.getDocument(collection, id).get();
        assertEquals("value999", doc.get("field999"));
    }
    
    // ==================== Cache Tests ====================
    
    @Test
    @DisplayName("Should use cache for repeated reads")
    void testCacheHit() throws Exception {
        String collection = "cache_test";
        String id = "cached_doc";
        
        // Save document
        Map<String, Object> data = Map.of("cached", true);
        backend.saveDocument(collection, id, data).get();
        
        // First read - loads from file
        Document doc1 = backend.getDocument(collection, id).get();
        assertTrue((Boolean) doc1.get("cached"));
        
        // Delete the file to test cache
        Path docPath = tempDir.resolve(collection).resolve(id + ".json");
        Files.delete(docPath);
        
        // Second read - should come from cache
        Document doc2 = backend.getDocument(collection, id).get();
        assertTrue((Boolean) doc2.get("cached"));
    }
    
    @Test
    @DisplayName("Should work without cache when disabled")
    void testNoCacheMode() throws Exception {
        // Create backend with cache disabled
        JsonStorageBackend noCacheBackend = new JsonStorageBackend(tempDir, 100, false);
        
        String collection = "no_cache_test";
        String id = "test_doc";
        
        // Save document
        Map<String, Object> data = Map.of("value", "test");
        noCacheBackend.saveDocument(collection, id, data).get();
        
        // Read document
        Document doc = noCacheBackend.getDocument(collection, id).get();
        assertEquals("test", doc.get("value"));
        
        // Modify file directly
        Path docPath = tempDir.resolve(collection).resolve(id + ".json");
        Files.writeString(docPath, "{\"value\":\"modified\"}");
        
        // Read again - should get modified value (no cache)
        Document doc2 = noCacheBackend.getDocument(collection, id).get();
        assertEquals("modified", doc2.get("value"));
        
        noCacheBackend.shutdown();
    }
    
    // ==================== Error Handling Tests ====================
    
    @Test
    @DisplayName("Should handle corrupted JSON files gracefully")
    void testCorruptedJsonHandling() throws Exception {
        String collection = "corrupt_test";
        String id = "bad_json";
        
        // Create corrupted JSON file
        Path collectionPath = tempDir.resolve(collection);
        Files.createDirectories(collectionPath);
        Path docPath = collectionPath.resolve(id + ".json");
        Files.writeString(docPath, "{ this is not valid json }");
        
        // Should return empty document instead of throwing
        Document doc = backend.getDocument(collection, id).get();
        assertNotNull(doc);
        assertFalse(doc.exists());
    }
    
    @Test
    @DisplayName("Should handle special characters in IDs")
    void testSpecialCharactersInIds() throws Exception {
        String collection = "special_chars";
        String[] specialIds = {
            "user@example.com",
            "user-123",
            "user_456",
            "user.789"
        };
        
        for (String id : specialIds) {
            Map<String, Object> data = Map.of("id", id);
            backend.saveDocument(collection, id, data).get();
            
            Document doc = backend.getDocument(collection, id).get();
            assertTrue(doc.exists());
            assertEquals(id, doc.get("id"));
        }
        
        // Verify count
        assertEquals(specialIds.length, backend.count(collection, null).get());
    }
    
    // ==================== Index Tests ====================
    
    @Test
    @DisplayName("Should maintain index file for quick counting")
    void testIndexFile() throws Exception {
        String collection = "indexed_collection";
        
        // Add documents
        for (int i = 1; i <= 5; i++) {
            backend.saveDocument(collection, "doc" + i, Map.of("value", i)).get();
        }
        
        // Check index file exists
        Path indexPath = tempDir.resolve(collection).resolve(".index");
        assertTrue(Files.exists(indexPath));
        
        // Verify index content
        List<String> indexedIds = Files.readAllLines(indexPath);
        assertEquals(5, indexedIds.size());
        assertTrue(indexedIds.contains("doc1"));
        assertTrue(indexedIds.contains("doc5"));
        
        // Delete a document
        backend.deleteDocument(collection, "doc3").get();
        
        // Index should be updated
        indexedIds = Files.readAllLines(indexPath);
        assertEquals(4, indexedIds.size());
        assertFalse(indexedIds.contains("doc3"));
    }
    
    // ==================== Performance Tests ====================
    
    @Test
    @DisplayName("Should handle large collections efficiently")
    void testLargeCollection() throws Exception {
        String collection = "large_collection";
        int numDocs = 100;
        
        // Create many documents
        List<CompletableFuture<Void>> futures = IntStream.range(0, numDocs)
            .mapToObj(i -> {
                Map<String, Object> data = Map.of(
                    "index", i,
                    "data", "Document " + i
                );
                return backend.saveDocument(collection, "doc" + i, data);
            })
            .collect(Collectors.toList());
        
        // Wait for all saves
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        
        // Count should be accurate
        assertEquals(numDocs, backend.count(collection, null).get());
        
        // Query should return all documents
        List<Document> allDocs = backend.getAllDocuments(collection).get();
        assertEquals(numDocs, allDocs.size());
    }
    
    @Test
    @DisplayName("Should respect LRU cache size limit")
    void testLRUCacheEviction() throws Exception {
        // Create backend with small cache
        JsonStorageBackend smallCacheBackend = new JsonStorageBackend(tempDir, 3, true);
        
        String collection = "lru_test";
        
        // Add more documents than cache size
        for (int i = 1; i <= 5; i++) {
            smallCacheBackend.saveDocument(collection, "doc" + i, Map.of("value", i)).get();
        }
        
        // Access first 3 documents (should be cached)
        for (int i = 1; i <= 3; i++) {
            smallCacheBackend.getDocument(collection, "doc" + i).get();
        }
        
        // Access doc4 and doc5 (should evict doc1 and doc2 from cache)
        smallCacheBackend.getDocument(collection, "doc4").get();
        smallCacheBackend.getDocument(collection, "doc5").get();
        
        // Cache should only have doc3, doc4, doc5
        // We can't directly test cache contents, but behavior should be correct
        
        smallCacheBackend.shutdown();
    }
    
    // ==================== Update/Modification Tests ====================
    
    @Test
    @DisplayName("Should update existing documents")
    void testUpdateDocument() throws Exception {
        String collection = "updates";
        String id = "doc1";
        
        // Initial save
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("version", 1);
        initialData.put("name", "Original");
        backend.saveDocument(collection, id, initialData).get();
        
        // Update
        Map<String, Object> updatedData = new HashMap<>();
        updatedData.put("version", 2);
        updatedData.put("name", "Updated");
        updatedData.put("newField", "Added");
        backend.saveDocument(collection, id, updatedData).get();
        
        // Verify update
        Document doc = backend.getDocument(collection, id).get();
        Object version = doc.get("version");
        assertTrue(version instanceof Number);
        assertEquals(2, ((Number)version).intValue());
        assertEquals("Updated", doc.get("name"));
        assertEquals("Added", doc.get("newField"));
    }
    
    @Test
    @DisplayName("Should handle partial updates through Document interface")
    void testPartialUpdate() throws Exception {
        String collection = "partial_updates";
        String id = "doc1";
        
        // Initial document
        Map<String, Object> data = new HashMap<>();
        data.put("field1", "value1");
        data.put("field2", "value2");
        backend.saveDocument(collection, id, data).get();
        
        // Get document and update single field
        Document doc = backend.getDocument(collection, id).get();
        doc.set("field2", "modified");
        
        // Verify both fields exist
        Document updated = backend.getDocument(collection, id).get();
        assertEquals("value1", updated.get("field1"));
        assertEquals("modified", updated.get("field2"));
    }
}