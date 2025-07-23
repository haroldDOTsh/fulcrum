package sh.harold.fulcrum.playerdata;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.registry.BackendResolver;

/**
 * Test utility that provides a mock BackendResolver for testing purposes.
 * This allows tests to work with the new single-parameter registerSchema method
 * without requiring full runtime initialization.
 */
public class TestBackendResolver implements BackendResolver {
    
    private final PlayerDataBackend mockBackend = new MockPlayerDataBackend();
    
    @Override
    public PlayerDataBackend resolveBackend(PlayerDataSchema<?> schema) {
        // For testing purposes, return a mock backend that supports basic operations
        // Tests are focused on DDL generation and schema registration
        return mockBackend;
    }
    
    @Override
    public PlayerDataBackend getStructuredBackend() {
        // Return mock backend for tests - sufficient for DDL generation testing
        return mockBackend;
    }
    
    @Override
    public PlayerDataBackend getDocumentBackend() {
        // Return mock backend for tests - sufficient for DDL generation testing
        return mockBackend;
    }
    
    /**
     * Sets up the test environment with a mock BackendResolver.
     * Call this in test setup methods to enable the single-parameter registerSchema.
     */
    public static void setupTestEnvironment() {
        sh.harold.fulcrum.api.data.registry.PlayerDataRegistry.setBackendResolver(new TestBackendResolver());
    }
    
    /**
     * Cleans up the test environment by removing the BackendResolver.
     * Call this in test teardown methods to ensure clean state.
     */
    public static void cleanupTestEnvironment() {
        sh.harold.fulcrum.api.data.registry.PlayerDataRegistry.setBackendResolver(null);
    }
}