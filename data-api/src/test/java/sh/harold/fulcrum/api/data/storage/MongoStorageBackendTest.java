package sh.harold.fulcrum.api.data.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.QueryImpl;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoStorageBackend;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers
@Disabled("Requires MongoDB server - tests require Testcontainers with Docker")
class MongoStorageBackendTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0")
            .withExposedPorts(27017);

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private MongoStorageBackend storageBackend;
    private MongoConnectionAdapter connectionAdapter;

    @BeforeAll
    static void setupContainer() {
        String connectionString = mongoDBContainer.getReplicaSetUrl();
        mongoClient = MongoClients.create(connectionString);
        database = mongoClient.getDatabase("test");
    }

    @AfterAll
    static void teardownContainer() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @BeforeEach
    void setup() {
        // Clear all collections before each test
        database.listCollectionNames().forEach(name -> database.getCollection(name).drop());

        connectionAdapter = new MongoConnectionAdapter(mongoDBContainer.getReplicaSetUrl(), "test");
        storageBackend = new MongoStorageBackend(connectionAdapter);
    }

    @AfterEach
    void teardown() {
        if (storageBackend != null) {
            storageBackend.close();
        }
    }

    @Test
    @DisplayName("Should save and retrieve a document")
    void testSaveAndRetrieveDocument() throws Exception {
        // Given
        String collection = "users";
        String id = "user1";
        Map<String, Object> data = new HashMap<>();
        data.put("name", "John Doe");
        data.put("age", 30);
        data.put("email", "john@example.com");

        // When
        CompletableFuture<Void> saveFuture = storageBackend.saveDocument(collection, id, data);
        saveFuture.get(5, TimeUnit.SECONDS);

        CompletableFuture<Document> getFuture = storageBackend.getDocument(collection, id);
        Document document = getFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertThat(document).isNotNull();
        assertThat(document.exists()).isTrue();
        assertThat(document.get("name")).isEqualTo("John Doe");
        assertThat(document.get("age")).isEqualTo(30L); // MongoDB returns Long for numbers
        assertThat(document.get("email")).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("Should handle nested document fields")
    void testNestedDocumentFields() throws Exception {
        // Given
        String collection = "profiles";
        String id = "profile1";
        Map<String, Object> address = new HashMap<>();
        address.put("street", "123 Main St");
        address.put("city", "New York");
        address.put("zip", "10001");

        Map<String, Object> data = new HashMap<>();
        data.put("name", "Jane Smith");
        data.put("address", address);

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(document.get("address.street")).isEqualTo("123 Main St");
        assertThat(document.get("address.city")).isEqualTo("New York");
        assertThat(document.get("address.zip")).isEqualTo("10001");
    }

    @Test
    @DisplayName("Should delete document successfully")
    void testDeleteDocument() throws Exception {
        // Given
        String collection = "items";
        String id = "item1";
        Map<String, Object> data = Map.of("name", "Test Item");

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        Boolean deleted = storageBackend.deleteDocument(collection, id).get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(deleted).isTrue();
        assertThat(document.exists()).isFalse();
    }

    @Test
    @DisplayName("Should return false when deleting non-existent document")
    void testDeleteNonExistentDocument() throws Exception {
        // When
        Boolean deleted = storageBackend.deleteDocument("collection", "nonexistent").get(5, TimeUnit.SECONDS);

        // Then
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("Should update field using atomic operations")
    void testUpdateField() throws Exception {
        // Given
        String collection = "counters";
        String id = "counter1";
        Map<String, Object> data = new HashMap<>();
        data.put("count", 10);
        data.put("name", "Test Counter");

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        storageBackend.updateField(collection, id, "count", 20).get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(document.get("count")).isEqualTo(20L); // MongoDB returns Long for numbers
        assertThat(document.get("name")).isEqualTo("Test Counter"); // Other fields unchanged
    }

    @Test
    @DisplayName("Should increment field atomically")
    void testIncrementField() throws Exception {
        // Given
        String collection = "stats";
        String id = "stat1";
        Map<String, Object> data = Map.of("views", 100);

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        storageBackend.incrementField(collection, id, "views", 5).get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(document.get("views")).isEqualTo(105L);
    }

    @Test
    @DisplayName("Should push to array field")
    void testPushToArray() throws Exception {
        // Given
        String collection = "lists";
        String id = "list1";
        Map<String, Object> data = new HashMap<>();
        data.put("tags", Arrays.asList("tag1", "tag2"));

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        storageBackend.pushToArray(collection, id, "tags", "tag3").get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) document.get("tags");
        assertThat(tags).containsExactly("tag1", "tag2", "tag3");
    }

    @Test
    @DisplayName("Should pull from array field")
    void testPullFromArray() throws Exception {
        // Given
        String collection = "lists";
        String id = "list1";
        Map<String, Object> data = new HashMap<>();
        data.put("items", Arrays.asList("item1", "item2", "item3"));

        // When
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);
        storageBackend.pullFromArray(collection, id, "items", "item2").get(5, TimeUnit.SECONDS);
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);

        // Then
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) document.get("items");
        assertThat(items).containsExactly("item1", "item3");
    }

    @Test
    @DisplayName("Should query documents with equals condition")
    void testQueryWithEquals() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.where("category").equalTo("electronics");
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(2);
        results.forEach(doc -> assertThat(doc.get("category")).isEqualTo("electronics"));
    }

    @Test
    @DisplayName("Should query documents with greater than condition")
    void testQueryWithGreaterThan() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.where("price").greaterThan(100);
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(2);
        results.forEach(doc -> {
            Number price = (Number) doc.get("price");
            assertThat(price.doubleValue()).isGreaterThan(100);
        });
    }

    @Test
    @DisplayName("Should query with AND conditions")
    void testQueryWithAndConditions() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.where("category").equalTo("electronics")
                .and("price").greaterThan(100);
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(1);
        Document doc = results.get(0);
        assertThat(doc.get("name")).isEqualTo("Laptop");
        assertThat(doc.get("category")).isEqualTo("electronics");
    }

    @Test
    @DisplayName("Should query with OR conditions")
    void testQueryWithOrConditions() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.where("category").equalTo("books")
                .or("price").lessThan(30);
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("Should apply sorting to query results")
    void testQueryWithSorting() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.sort("price", false); // Descending
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(4);
        assertThat(results.get(0).get("name")).isEqualTo("Laptop");
        assertThat(results.get(3).get("name")).isEqualTo("Book");
    }

    @Test
    @DisplayName("Should apply limit and skip to query")
    void testQueryWithPagination() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.sort("price", true).skip(1).limit(2);
        List<Document> results = storageBackend.query(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("name")).isEqualTo("Headphones");
        assertThat(results.get(1).get("name")).isEqualTo("Chair");
    }

    @Test
    @DisplayName("Should count documents matching query")
    void testCountWithQuery() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        QueryImpl query = new QueryImpl(collection, storageBackend);
        query.where("category").equalTo("electronics");
        Long count = storageBackend.count(collection, query).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Should count all documents when query is null")
    void testCountAllDocuments() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        Long count = storageBackend.count(collection, null).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(count).isEqualTo(4);
    }

    @Test
    @DisplayName("Should get all documents in collection")
    void testGetAllDocuments() throws Exception {
        // Given
        String collection = "products";
        saveTestDocuments(collection);

        // When
        List<Document> documents = storageBackend.getAllDocuments(collection).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(documents).hasSize(4);
    }

    @Test
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws Exception {
        // Given
        String collection = "concurrent";
        String id = "counter";
        Map<String, Object> data = Map.of("value", 0);
        storageBackend.saveDocument(collection, id, data).get(5, TimeUnit.SECONDS);

        int threadCount = 10;
        int incrementsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        storageBackend.incrementField(collection, id, "value", 1).get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));

        // Then
        assertThat(errors.get()).isZero();
        Document document = storageBackend.getDocument(collection, id).get(5, TimeUnit.SECONDS);
        assertThat(document.get("value")).isEqualTo((long) (threadCount * incrementsPerThread));
    }

    @Test
    @DisplayName("Should handle transaction rollback on failure")
    void testTransactionRollback() throws Exception {
        // Given
        String collection = "accounts";
        String account1 = "acc1";
        String account2 = "acc2";

        Map<String, Object> data1 = Map.of("balance", 1000);
        Map<String, Object> data2 = Map.of("balance", 500);

        storageBackend.saveDocument(collection, account1, data1).get(5, TimeUnit.SECONDS);
        storageBackend.saveDocument(collection, account2, data2).get(5, TimeUnit.SECONDS);

        // When - Attempt a transaction that should fail
        try {
            storageBackend.executeInTransaction(session -> {
                // Transfer 1500 (more than available)
                storageBackend.incrementField(collection, account1, "balance", -1500).join();

                // This should trigger a rollback
                Document acc1 = storageBackend.getDocument(collection, account1).join();
                if ((Long) acc1.get("balance") < 0) {
                    throw new RuntimeException("Insufficient funds");
                }

                storageBackend.incrementField(collection, account2, "balance", 1500).join();
                return null;
            }).get(5, TimeUnit.SECONDS);

            fail("Transaction should have failed");
        } catch (Exception e) {
            // Expected
        }

        // Then - Verify balances are unchanged
        Document acc1 = storageBackend.getDocument(collection, account1).get(5, TimeUnit.SECONDS);
        Document acc2 = storageBackend.getDocument(collection, account2).get(5, TimeUnit.SECONDS);

        assertThat(acc1.get("balance")).isEqualTo(1000L);
        assertThat(acc2.get("balance")).isEqualTo(500L);
    }

    // Helper method to save test documents
    private void saveTestDocuments(String collection) throws Exception {
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("name", "Laptop");
        doc1.put("category", "electronics");
        doc1.put("price", 999.99);
        storageBackend.saveDocument(collection, "prod1", doc1).get(5, TimeUnit.SECONDS);

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("name", "Book");
        doc2.put("category", "books");
        doc2.put("price", 19.99);
        storageBackend.saveDocument(collection, "prod2", doc2).get(5, TimeUnit.SECONDS);

        Map<String, Object> doc3 = new HashMap<>();
        doc3.put("name", "Headphones");
        doc3.put("category", "electronics");
        doc3.put("price", 79.99);
        storageBackend.saveDocument(collection, "prod3", doc3).get(5, TimeUnit.SECONDS);

        Map<String, Object> doc4 = new HashMap<>();
        doc4.put("name", "Chair");
        doc4.put("category", "furniture");
        doc4.put("price", 150.00);
        storageBackend.saveDocument(collection, "prod4", doc4).get(5, TimeUnit.SECONDS);
    }
}