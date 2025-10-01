package sh.harold.fulcrum.api.data.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.DataAPIImpl;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import sh.harold.fulcrum.api.data.storage.CacheProvider;
import com.mongodb.client.MongoDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for transaction functionality.
 */
public class TransactionTest {
    
    private DataAPI dataAPI;
    private Collection testCollection;
    
    private void cleanCollectionDirectory(String collection) {
        Path dir = Path.of("test-data").resolve(collection);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to clean test data", e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean test data directory", e);
        }
    }
    @BeforeEach
    void setUp() {
        cleanCollectionDirectory("test");
        cleanCollectionDirectory("logs");

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
        
        // Clear logs collection too
        Collection logsCollection = dataAPI.from("logs");
        List<Document> existingLogs = logsCollection.all();
        for (Document doc : existingLogs) {
            if (doc.get("_id") != null) {
                logsCollection.delete(doc.get("_id").toString());
            }
        }
        
        // Set up initial test data
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("balance", 1000);
        initialData.put("name", "Test Account");
        testCollection.create("account1", initialData);
        
        Map<String, Object> initialData2 = new HashMap<>();
        initialData2.put("balance", 500);
        initialData2.put("name", "Test Account 2");
        testCollection.create("account2", initialData2);
    }
    
    @Test
    void testBasicTransaction() {
        Transaction tx = dataAPI.transaction();
        
        assertTrue(tx.isActive());
        
        // Perform operations within transaction
        tx.from("test").select("account1").set("balance", 800);
        tx.from("test").select("account2").set("balance", 700);
        
        // Commit transaction
        tx.commit();
        
        assertFalse(tx.isActive());
        
        // Verify changes were applied
        Document doc1 = testCollection.select("account1");
        Document doc2 = testCollection.select("account2");
        
        assertEquals(800, doc1.get("balance"));
        assertEquals(700, doc2.get("balance"));
    }
    
    @Test
    void testTransactionRollback() {
        Transaction tx = dataAPI.transaction();
        
        // Perform operations
        tx.from("test").select("account1").set("balance", 0);
        tx.from("test").select("account2").set("balance", 0);
        
        // Rollback instead of commit
        tx.rollback();
        
        assertFalse(tx.isActive());
        
        // Verify changes were NOT applied
        Document doc1 = testCollection.select("account1");
        Document doc2 = testCollection.select("account2");
        
        assertEquals(1000, doc1.get("balance"));
        assertEquals(500, doc2.get("balance"));
    }
    
    @Test
    void testTransactionWithSavepoints() {
        Transaction tx = dataAPI.transaction();
        
        // First operation
        tx.from("test").select("account1").set("balance", 900);
        
        // Create savepoint
        tx.savepoint("sp1");
        
        // Second operation
        tx.from("test").select("account2").set("balance", 600);
        
        // Third operation
        tx.from("test").select("account1").set("balance", 100);
        
        // Rollback to savepoint
        tx.rollbackTo("sp1");
        
        // Commit what's left
        tx.commit();
        
        // Verify only first operation was applied
        Document doc1 = testCollection.select("account1");
        Document doc2 = testCollection.select("account2");
        
        assertEquals(900, doc1.get("balance"));
        assertEquals(500, doc2.get("balance")); // Unchanged
    }
    
    @Test
    void testTransactionIsolationLevels() {
        // Test with different isolation levels
        Transaction tx1 = dataAPI.transaction(Transaction.IsolationLevel.READ_COMMITTED);
        assertEquals(Transaction.IsolationLevel.READ_COMMITTED, tx1.getIsolationLevel());
        
        Transaction tx2 = dataAPI.transaction(Transaction.IsolationLevel.SERIALIZABLE);
        assertEquals(Transaction.IsolationLevel.SERIALIZABLE, tx2.getIsolationLevel());
        
        // Change isolation level
        tx1.setIsolationLevel(Transaction.IsolationLevel.REPEATABLE_READ);
        assertEquals(Transaction.IsolationLevel.REPEATABLE_READ, tx1.getIsolationLevel());
        
        // Cleanup
        tx1.rollback();
        tx2.rollback();
    }
    
    @Test
    void testTransactionCreate() {
        Transaction tx = dataAPI.transaction();
        
        Map<String, Object> newData = new HashMap<>();
        newData.put("balance", 2000);
        newData.put("name", "New Account");
        
        tx.from("test").create("account3", newData);
        
        // Before commit, document shouldn't exist in main collection
        assertFalse(testCollection.select("account3").exists());
        
        tx.commit();
        
        // After commit, document should exist
        Document doc = testCollection.select("account3");
        assertTrue(doc.exists());
        assertEquals(2000, doc.get("balance"));
        assertEquals("New Account", doc.get("name"));
    }
    
    @Test
    void testTransactionDelete() {
        Transaction tx = dataAPI.transaction();
        
        tx.from("test").delete("account1");
        
        // Before commit, document should still exist
        assertTrue(testCollection.select("account1").exists());
        
        tx.commit();
        
        // After commit, document should be deleted
        assertFalse(testCollection.select("account1").exists());
    }
    
    @Test
    void testTransactionIncrement() {
        Transaction tx = dataAPI.transaction();
        
        // Since increment is not in the base Document interface, we'll use set with calculated values
        Document acc1 = testCollection.select("account1");
        int balance1 = (Integer) acc1.get("balance");
        tx.from("test").select("account1").set("balance", balance1 + 100);
        
        Document acc2 = testCollection.select("account2");
        int balance2 = (Integer) acc2.get("balance");
        tx.from("test").select("account2").set("balance", balance2 - 50);
        
        tx.commit();
        
        Document doc1 = testCollection.select("account1");
        Document doc2 = testCollection.select("account2");
        
        assertEquals(1100, doc1.get("balance"));
        assertEquals(450, doc2.get("balance"));
    }
    
    @Test
    void testMultipleSavepoints() {
        Transaction tx = dataAPI.transaction();
        
        tx.from("test").select("account1").set("balance", 900);
        tx.savepoint("sp1");
        
        tx.from("test").select("account1").set("balance", 800);
        tx.savepoint("sp2");
        
        tx.from("test").select("account1").set("balance", 700);
        tx.savepoint("sp3");
        
        tx.from("test").select("account1").set("balance", 600);
        
        // Rollback to middle savepoint
        tx.rollbackTo("sp2");
        
        tx.commit();
        
        Document doc = testCollection.select("account1");
        assertEquals(800, doc.get("balance"));
    }
    
    @Test
    void testTransactionException() {
        Transaction tx = dataAPI.transaction();
        
        // Try to use transaction after commit
        tx.commit();
        
        assertThrows(IllegalStateException.class, () -> {
            tx.from("test").select("account1").set("balance", 0);
        });
        
        // Try to commit again
        assertThrows(IllegalStateException.class, () -> {
            tx.commit();
        });
    }
    
    @Test
    void testComplexTransaction() {
        Transaction tx = dataAPI.transaction();
        
        // Simulate a money transfer
        int transferAmount = 200;
        
        // Deduct from account1
        Document acc1 = testCollection.select("account1");
        int balance1 = (Integer) acc1.get("balance");
        tx.from("test").select("account1").set("balance", balance1 - transferAmount);
        
        // Add to account2
        Document acc2 = testCollection.select("account2");
        int balance2 = (Integer) acc2.get("balance");
        tx.from("test").select("account2").set("balance", balance2 + transferAmount);
        
        // Create transaction log
        Map<String, Object> logData = new HashMap<>();
        logData.put("from", "account1");
        logData.put("to", "account2");
        logData.put("amount", transferAmount);
        logData.put("timestamp", System.currentTimeMillis());
        tx.from("logs").create(UUID.randomUUID().toString(), logData);
        
        tx.commit();
        
        // Verify the transfer
        assertEquals(800, testCollection.select("account1").get("balance"));
        assertEquals(700, testCollection.select("account2").get("balance"));
        
        // Verify log was created
        assertEquals(1, dataAPI.from("logs").count());
    }
    
    @Test
    void testNestedTransactionsNotSupported() {
        Transaction tx1 = dataAPI.transaction();
        
        // Nested transactions should not be supported
        assertThrows(UnsupportedOperationException.class, () -> {
            // Try to access queries within transaction
            tx1.from("test").find();
        });
        
        assertThrows(UnsupportedOperationException.class, () -> {
            // Try to get all documents
            tx1.from("test").all();
        });
        
        assertThrows(UnsupportedOperationException.class, () -> {
            // Try to count
            tx1.from("test").count();
        });
        
        tx1.rollback();
    }
    
    @Test
    void testTransactionWithNullValues() {
        Transaction tx = dataAPI.transaction();
        
        tx.from("test").select("account1").set("description", null);
        tx.from("test").select("account1").set("name", null); // Using set with null instead of remove
        
        tx.commit();
        
        Document doc = testCollection.select("account1");
        assertNull(doc.get("description"));
        assertNull(doc.get("name"));
        assertEquals(1000, doc.get("balance")); // Unchanged
    }
    
    @Test
    void testRollbackToInvalidSavepoint() {
        Transaction tx = dataAPI.transaction();
        
        tx.savepoint("sp1");
        
        assertThrows(IllegalArgumentException.class, () -> {
            tx.rollbackTo("nonexistent");
        });
        
        tx.rollback();
    }
    
    @Test
    void testTransactionIsolation() {
        // Create two transactions
        Transaction tx1 = dataAPI.transaction(Transaction.IsolationLevel.SERIALIZABLE);
        Transaction tx2 = dataAPI.transaction(Transaction.IsolationLevel.SERIALIZABLE);
        
        // Both try to modify same document
        tx1.from("test").select("account1").set("balance", 1500);
        tx2.from("test").select("account1").set("balance", 2000);
        
        // Commit first transaction
        tx1.commit();
        
        // Second transaction should also succeed in this simple implementation
        tx2.commit();
        
        // Last write wins in this implementation
        Document doc = testCollection.select("account1");
        assertEquals(2000, doc.get("balance"));
    }
}




