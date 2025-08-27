package sh.harold.fulcrum.environment;

import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.EnvironmentConfigParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.api.module.BootstrapContextHolder;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the new environment.yml system
 */
public class EnvironmentSystemTest {
    
    @BeforeEach
    public void setUp() {
        // Reset FulcrumEnvironment state before each test
        try {
            java.lang.reflect.Field initializedField = FulcrumEnvironment.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.set(null, false);
            
            java.lang.reflect.Field environmentField = FulcrumEnvironment.class.getDeclaredField("currentEnvironment");
            environmentField.setAccessible(true);
            environmentField.set(null, null);
            
            java.lang.reflect.Field configField = FulcrumEnvironment.class.getDeclaredField("environmentConfig");
            configField.setAccessible(true);
            configField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset FulcrumEnvironment state", e);
        }
        
        // Clear any bootstrap context
        BootstrapContextHolder.clearContext();
    }
    
    @Test
    public void testEnvironmentConfigParsing() {
        // Test configuration parsing
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core", "identity-module"),
            "dev", Set.of("debug-module", "test-module"),
            "lobby", Set.of("lobby-module", "scoreboard-module")
        );
        
        EnvironmentConfig config = new EnvironmentConfig(testConfig);
        
        // Test global modules
        assertTrue(config.getGlobalModules().contains("fulcrum-core"));
        assertTrue(config.getGlobalModules().contains("identity-module"));
        
        // Test environment-specific modules
        assertTrue(config.getModulesForEnvironment("dev").contains("debug-module"));
        assertTrue(config.getModulesForEnvironment("lobby").contains("lobby-module"));
        
        // Test module enablement logic
        assertTrue(config.isModuleEnabled("fulcrum-core", "dev")); // Global module
        assertTrue(config.isModuleEnabled("debug-module", "dev")); // Environment-specific
        assertFalse(config.isModuleEnabled("lobby-module", "dev")); // Wrong environment
    }
    
    @Test
    public void testFulcrumEnvironmentWithConfiguration() {
        // Create test configuration
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core"),
            "dev", Set.of("debug-module"),
            "lobby", Set.of("lobby-module")
        );
        
        // Initialize FulcrumEnvironment with new configuration-based approach
        FulcrumEnvironment.initialize("dev", testConfig);
        
        // Test basic functionality
        assertEquals("dev", FulcrumEnvironment.getCurrent());
        
        // Test module enablement with context
        try {
            BootstrapContextHolder.setContext("fulcrum-core");
            assertTrue(FulcrumEnvironment.isThisModuleEnabled()); // Global module
        } finally {
            BootstrapContextHolder.clearContext();
        }
        
        try {
            BootstrapContextHolder.setContext("debug-module");
            assertTrue(FulcrumEnvironment.isThisModuleEnabled()); // Dev environment module
        } finally {
            BootstrapContextHolder.clearContext();
        }
        
        try {
            BootstrapContextHolder.setContext("lobby-module");
            assertFalse(FulcrumEnvironment.isThisModuleEnabled()); // Lobby environment module, not in dev
        } finally {
            BootstrapContextHolder.clearContext();
        }
    }
    
    @Test
    public void testFulcrumEnvironmentWithoutContext() {
        // Initialize with configuration
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core"),
            "dev", Set.of("debug-module")
        );
        
        FulcrumEnvironment.initialize("dev", testConfig);
        
        // When not in bootstrap phase and no legacy registry, it returns true by default
        // This is the legacy behavior for backward compatibility
        assertTrue(FulcrumEnvironment.isThisModuleEnabled());
    }
    
    @Test
    public void testFulcrumEnvironmentWithoutContextInBootstrapPhase() {
        // Initialize with configuration
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core"),
            "dev", Set.of("debug-module")
        );
        
        FulcrumEnvironment.initialize("dev", testConfig);
        
        // Simulate being in bootstrap phase without setting module ID
        try {
            // Set bootstrap phase flag but not module ID
            BootstrapContextHolder.setContext(null);
            
            // This should throw an exception
            assertThrows(IllegalStateException.class, () -> {
                FulcrumEnvironment.isThisModuleEnabled();
            });
        } finally {
            BootstrapContextHolder.clearContext();
        }
    }
    
    @Test
    public void testEnvironmentConfigParser() {
        EnvironmentConfigParser parser = new EnvironmentConfigParser();
        
        // Test with non-existent file (should return empty config)
        EnvironmentConfig config = parser.loadConfiguration("nonexistent.yml");
        assertNotNull(config);
        assertTrue(config.getGlobalModules().isEmpty());
    }
    
    @Test
    public void testLegacyCompatibility() {
        // Test that initialization with null config still works (legacy behavior)
        FulcrumEnvironment.initialize("test-env", null);
        assertEquals("test-env", FulcrumEnvironment.getCurrent());
        
        // With null config, all modules are enabled by default
        try {
            BootstrapContextHolder.setContext("any-module");
            assertTrue(FulcrumEnvironment.isThisModuleEnabled());
        } finally {
            BootstrapContextHolder.clearContext();
        }
    }
    
    @Test
    public void testModuleEnablementWithEmptyConfig() {
        // Test with empty configuration
        FulcrumEnvironment.initialize("dev", Map.of());
        
        // With empty config, modules are NOT enabled by default
        // because they're not in any environment list
        try {
            BootstrapContextHolder.setContext("any-module");
            assertFalse(FulcrumEnvironment.isThisModuleEnabled());
        } finally {
            BootstrapContextHolder.clearContext();
        }
    }
    
    @Test
    public void testModuleEnablementWithNullConfig() {
        // Test with null configuration (legacy mode)
        FulcrumEnvironment.initialize("dev", (Map<String, Set<String>>)null);
        
        // With null config, all modules are enabled by default
        try {
            BootstrapContextHolder.setContext("any-module");
            assertTrue(FulcrumEnvironment.isThisModuleEnabled());
        } finally {
            BootstrapContextHolder.clearContext();
        }
    }
    
    @Test
    public void testBootstrapContextThreadSafety() throws InterruptedException {
        // Initialize environment
        FulcrumEnvironment.initialize("dev", Map.of(
            "dev", Set.of("module1", "module2")
        ));
        
        // Test that context is thread-local
        Thread thread1 = new Thread(() -> {
            try {
                BootstrapContextHolder.setContext("module1");
                assertEquals("module1", BootstrapContextHolder.getCurrentModuleId());
                assertTrue(BootstrapContextHolder.isInBootstrapPhase());
                assertTrue(FulcrumEnvironment.isThisModuleEnabled());
            } finally {
                BootstrapContextHolder.clearContext();
            }
        });
        
        Thread thread2 = new Thread(() -> {
            try {
                BootstrapContextHolder.setContext("module3");
                assertEquals("module3", BootstrapContextHolder.getCurrentModuleId());
                assertTrue(BootstrapContextHolder.isInBootstrapPhase());
                assertFalse(FulcrumEnvironment.isThisModuleEnabled()); // module3 not in dev
            } finally {
                BootstrapContextHolder.clearContext();
            }
        });
        
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        
        // Main thread should have no context
        assertNull(BootstrapContextHolder.getCurrentModuleId());
        assertFalse(BootstrapContextHolder.isInBootstrapPhase());
    }
}