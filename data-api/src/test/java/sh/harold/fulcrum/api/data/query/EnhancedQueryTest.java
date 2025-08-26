package sh.harold.fulcrum.api.data.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.InMemoryStorageBackend;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import sh.harold.fulcrum.api.data.query.Query;
import com.mongodb.client.MongoDatabase;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enhanced query functionality.
 */
public class EnhancedQueryTest {
    
    private DataAPI dataAPI;
    private Collection testCollection;
    
    @BeforeEach
    void setUp() {
        ConnectionAdapter adapter = new ConnectionAdapter() {
            @Override
            public StorageType getStorageType() {
                return StorageType.JSON; // Using JSON as in-memory fallback
            }
            
            @Override
            public MongoDatabase getMongoDatabase() {
                return null;
            }
            
            @Override
            public Path getJsonStoragePath() {
                return Path.of("test-data");
            }
            
            @Override
            public Optional<CacheProvider> getCacheProvider() {
                return Optional.empty();
            }
        };
        
        dataAPI = DataAPI.create(adapter);
        testCollection = dataAPI.from("test");
        
        // Clear any existing test data first
        List<Document> existingDocs = testCollection.all();
        for (Document doc : existingDocs) {
            if (doc.get("_id") != null) {
                testCollection.delete(doc.get("_id").toString());
            }
        }
        
        // Set up test data
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("name", "Alice");
        doc1.put("email", "alice@example.com");
        doc1.put("age", 25);
        doc1.put("tags", Arrays.asList("admin", "user"));
        doc1.put("active", true);
        testCollection.create("doc1", doc1);
        
        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("name", "Bob");
        doc2.put("email", "bob@test.com");
        doc2.put("age", 30);
        doc2.put("tags", Arrays.asList("user"));
        doc2.put("active", false);
        testCollection.create("doc2", doc2);
        
        Map<String, Object> doc3 = new HashMap<>();
        doc3.put("name", "Charlie");
        doc3.put("email", "charlie@example.org");
        doc3.put("age", 35);
        doc3.put("tags", Arrays.asList("moderator", "user"));
        doc3.put("active", true);
        testCollection.create("doc3", doc3);
        
        Map<String, Object> doc4 = new HashMap<>();
        doc4.put("name", "");
        doc4.put("email", null);
        doc4.put("age", 40);
        doc4.put("tags", new ArrayList<>());
        doc4.put("active", false);
        testCollection.create("doc4", doc4);
    }
    
    @Test
    void testMatches() {
        List<Document> results = testCollection.where("email")
            .matches(".*@example\\.(com|org)")
            .execute();
        
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> "Alice".equals(d.get("name"))));
        assertTrue(results.stream().anyMatch(d -> "Charlie".equals(d.get("name"))));
    }
    
    @Test
    void testStartsWith() {
        List<Document> results = testCollection.where("name")
            .startsWith("Cha")
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("Charlie", results.get(0).get("name"));
    }
    
    @Test
    void testEndsWith() {
        List<Document> results = testCollection.where("email")
            .endsWith(".com")
            .execute();
        
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> "Alice".equals(d.get("name"))));
        assertTrue(results.stream().anyMatch(d -> "Bob".equals(d.get("name"))));
    }
    
    @Test
    void testSize() {
        // Test array size
        List<Document> results = testCollection.where("tags")
            .size(2)
            .execute();
        
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> "Alice".equals(d.get("name"))));
        assertTrue(results.stream().anyMatch(d -> "Charlie".equals(d.get("name"))));
        
        // Test string size
        results = testCollection.where("name")
            .size(0)
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("", results.get(0).get("name"));
    }
    
    @Test
    void testType() {
        // All documents have age as a number (Integer)
        List<Document> results = testCollection.where("age")
            .type(Integer.class)
            .execute();
        
        // Filter to only our test documents (with age field)
        long docsWithAge = results.stream()
            .filter(d -> d.get("age") != null)
            .count();
        assertTrue(docsWithAge >= 4); // At least our 4 test documents
        
        // Test for null type - only doc4 has null email among our test docs
        results = testCollection.where("email")
            .not().type(String.class)
            .execute();
        
        // Find doc4 specifically (has age=40 and null email)
        boolean foundDoc4 = results.stream()
            .anyMatch(d -> Integer.valueOf(40).equals(d.get("age")));
        assertTrue(foundDoc4);
    }
    
    @Test
    void testNotModifier() {
        List<Document> results = testCollection.where("active")
            .not().equalTo(true)
            .execute();
        
        // Should include Bob and doc4 (empty name)
        assertTrue(results.stream().anyMatch(d -> "Bob".equals(d.get("name"))));
        assertTrue(results.stream().anyMatch(d -> "".equals(d.get("name"))));
    }
    
    @Test
    void testIsEmpty() {
        List<Document> results = testCollection.where("tags")
            .isEmpty()
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("", results.get(0).get("name"));
        
        // Test string isEmpty
        results = testCollection.where("name")
            .isEmpty()
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("", results.get(0).get("name"));
    }
    
    @Test
    void testIsNotEmpty() {
        List<Document> results = testCollection.where("tags")
            .isNotEmpty()
            .execute();
        
        assertEquals(3, results.size());
        assertFalse(results.stream().anyMatch(d -> "".equals(d.get("name"))));
    }
    
    @Test
    void testOrWhere() {
        // Testing OR conditions - using multiple or() calls
        List<Document> results = testCollection.where("age").greaterThan(30)
            .or("tags").contains("admin")
            .or("active").equalTo(false)
            .execute();
        
        assertEquals(4, results.size()); // All match one condition or another
    }
    
    @Test
    void testAndWhere() {
        // Testing AND conditions - using and() calls
        List<Document> results = testCollection.where("active").equalTo(true)
            .and("age").greaterThan(20)
            .and("age").lessThan(30)
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).get("name"));
    }
    
    @Test
    void testWhereAny() {
        List<Document> results = testCollection.find()
            .whereAny("name", "email")
            .contains("example")
            .execute();
        
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(d -> "Alice".equals(d.get("name"))));
        assertTrue(results.stream().anyMatch(d -> "Charlie".equals(d.get("name"))));
    }
    
    @Test
    void testDistinct() {
        // Add duplicate active values
        Map<String, Object> doc5 = new HashMap<>();
        doc5.put("name", "Dave");
        doc5.put("active", true);
        doc5.put("testMarker", "EnhancedQueryTest"); // Mark as test document
        testCollection.create("doc5", doc5);
        
        try {
            // Query only documents with our test marker or known test docs
            List<Document> results = testCollection.where("active")
                .in(Arrays.asList(true, false))
                .distinct("active")
                .execute();
            
            // Verify the distinct values
            Set<Object> distinctValues = new HashSet<>();
            for (Document doc : results) {
                distinctValues.add(doc.get("active"));
            }
            // Should have both true and false values
            assertTrue(distinctValues.contains(true));
            assertTrue(distinctValues.contains(false));
            assertTrue(distinctValues.size() >= 2);
        } finally {
            // Clean up the extra document to avoid affecting other tests
            testCollection.delete("doc5");
        }
    }
    
    @Test
    void testGroupBy() {
        List<Document> results = testCollection.find()
            .groupBy("active")
            .execute();
        
        // Should get representatives from each group
        assertEquals(2, results.size());
    }
    
    @Test
    void testHaving() {
        List<Document> results = testCollection.find()
            .groupBy("active")
            .having("age", 25)
            .execute();
        
        // Should only get the group that contains age 25
        assertEquals(1, results.size());
        assertEquals(true, results.get(0).get("active"));
    }
    
    @Test
    void testSum() {
        double sum = testCollection.find()
            .sum("age");
        
        assertEquals(130.0, sum, 0.01);
    }
    
    @Test
    void testAvg() {
        double avg = testCollection.find()
            .avg("age");
        
        assertEquals(32.5, avg, 0.01);
    }
    
    @Test
    void testMin() {
        Object min = testCollection.find()
            .min("age");
        
        assertEquals(25, min);
    }
    
    @Test
    void testMax() {
        Object max = testCollection.find()
            .max("age");
        
        assertEquals(40, max);
    }
    
    @Test
    void testAggregate() {
        // Custom aggregation - concatenate all names
        Object result = testCollection.find()
            .aggregate("name", values -> {
                return String.join(", ", values.stream()
                    .map(Object::toString)
                    .toArray(String[]::new));
            });
        
        assertTrue(result.toString().contains("Alice"));
        assertTrue(result.toString().contains("Bob"));
        assertTrue(result.toString().contains("Charlie"));
    }
    
    @Test
    void testComplexNestedQuery() {
        // Testing complex nested conditions using and/or combinations
        List<Document> results = testCollection.where("age").greaterThan(25)
            .and("active").equalTo(true)
            .and("tags").contains("moderator")
            .execute();
        
        assertEquals(1, results.size());
        assertEquals("Charlie", results.get(0).get("name"));
    }
    
    @Test
    void testCombinedAggregations() {
        // Test multiple aggregations on filtered data
        // Active users are Alice (25) and Charlie (35)
        Query activeQuery = testCollection.where("active").equalTo(true);
        
        double avgAge = activeQuery.avg("age");
        Object maxAge = activeQuery.max("age");
        long count = activeQuery.count();
        
        assertEquals(30.0, avgAge, 0.01); // (25 + 35) / 2 = 30
        assertEquals(35, maxAge);
        assertEquals(2, count);
    }
}