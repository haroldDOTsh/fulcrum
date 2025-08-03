package sh.harold.fulcrum.environment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;

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
            
            java.lang.reflect.Field registryField = FulcrumEnvironment.class.getDeclaredField("moduleRegistry");
            registryField.setAccessible(true);
            registryField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset FulcrumEnvironment state", e);
        }
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
    public void testModuleEnvironmentRegistry() {
        // Create test configuration
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core"),
            "dev", Set.of("debug-module"),
            "lobby", Set.of("lobby-module")
        );
        
        EnvironmentConfig config = new EnvironmentConfig(testConfig);
        ModuleEnvironmentRegistry registry = new ModuleEnvironmentRegistry(config, "dev");
        
        // Test direct module enablement
        assertTrue(registry.isModuleEnabled("fulcrum-core")); // Global module
        assertTrue(registry.isModuleEnabled("debug-module")); // Dev environment module
        assertFalse(registry.isModuleEnabled("lobby-module")); // Lobby environment module
    }
    
    @Test
    public void testFulcrumEnvironmentInitialization() {
        // Create test configuration
        Map<String, Set<String>> testConfig = Map.of(
            "global", Set.of("fulcrum-core"),
            "dev", Set.of("debug-module")
        );
        
        EnvironmentConfig config = new EnvironmentConfig(testConfig);
        ModuleEnvironmentRegistry registry = new ModuleEnvironmentRegistry(config, "dev");
        
        // Initialize FulcrumEnvironment
        FulcrumEnvironment.initialize("dev", registry);
        
        // Test basic functionality
        assertEquals("dev", FulcrumEnvironment.getCurrent());
        
        // Note: We can't easily test isThisModuleEnabled() without a proper plugin context
        // In real usage, this would be tested with actual plugins
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
        // Test that legacy initialization still works
        FulcrumEnvironment.initialize("test-env");
        assertEquals("test-env", FulcrumEnvironment.getCurrent());
        
        // Legacy isEnabledFor method should still work
        assertTrue(FulcrumEnvironment.isEnabledFor("test-env", "other-env"));
        assertFalse(FulcrumEnvironment.isEnabledFor("different-env"));
    }
    
    @Test
    public void testModuleEnablementWithEmptyConfig() {
        // Test with empty configuration (should default to enabled)
        EnvironmentConfig config = new EnvironmentConfig(Map.of());
        ModuleEnvironmentRegistry registry = new ModuleEnvironmentRegistry(config, "dev");
        
        // With empty config, no modules are explicitly enabled
        assertFalse(registry.isModuleEnabled("any-module"));
    }
}