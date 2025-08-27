package sh.harold.fulcrum;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.EnvironmentConfigParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bootstrap class for Fulcrum that initializes the environment configuration selection system.
 *
 * The system works as follows:
 * 1. environment.yml defines possible server configurations and their associated modules
 * 2. The ENVIRONMENT file selects which configuration this server should use
 * 3. This happens during bootstrap phase to control which plugins get loaded
 * 4. The selected configuration is reported during server registration
 *
 * @since 1.2.0
 */
@SuppressWarnings("UnstableApiUsage")
public class FulcrumBootstrapper implements PluginBootstrap {
    private static final String ENVIRONMENT_FILE = "ENVIRONMENT";
    private static final String DEFAULT_ENVIRONMENT = "dev";
    
    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        ComponentLogger logger = context.getLogger();
        
        // Detect current environment
        String environment = detectEnvironment(logger);
        logger.info("Detected environment: " + environment);
        
        // Load environment configuration
        EnvironmentConfig config = loadEnvironmentConfiguration(logger);
        
        // Convert EnvironmentConfig to Map<String, Set<String>> for the new API
        Map<String, Set<String>> configMap = convertConfigToMap(config);
        
        try {
            // Initialize FulcrumEnvironment with the selected configuration
            FulcrumEnvironment.initialize(environment, configMap);
            logger.info("Fulcrum environment initialized with configuration: " + environment);
            
            // Log which modules will be loaded for this configuration
            logConfigurationSummary(logger, config, environment);
            
        } catch (Exception e) {
            logger.error("Failed to initialize FulcrumEnvironment: " + e.getMessage());
            throw new RuntimeException("Fulcrum bootstrap failed", e);
        }
    }
    
    @NotNull
    @Override
    public JavaPlugin createPlugin(@NotNull PluginProviderContext context) {
        return new FulcrumPlugin();
    }
    
    /**
     * Detects the current server configuration from the ENVIRONMENT file.
     * The ENVIRONMENT file should contain the name of a configuration defined in environment.yml.
     */
    private String detectEnvironment(ComponentLogger logger) {
        Path serverRoot = Path.of(".");
        Path environmentFile = serverRoot.resolve(ENVIRONMENT_FILE);
        
        try {
            if (Files.exists(environmentFile)) {
                String configuration = Files.readString(environmentFile).trim();
                if (!configuration.isEmpty()) {
                    logger.info("Server configuration selected from ENVIRONMENT file: " + configuration);
                    return configuration;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read ENVIRONMENT file: " + e.getMessage());
        }
        
        logger.info("No ENVIRONMENT file found, using default configuration: " + DEFAULT_ENVIRONMENT);
        logger.info("Create an ENVIRONMENT file containing a configuration name from environment.yml to select a specific server configuration");
        return DEFAULT_ENVIRONMENT;
    }
    
    /**
     * Loads environment configuration from environment.yml
     */
    private EnvironmentConfig loadEnvironmentConfiguration(ComponentLogger logger) {
        EnvironmentConfigParser parser = new EnvironmentConfigParser();
        
        // Ensure environment.yml exists, generate default if needed
        ensureEnvironmentConfiguration(parser, logger);
        
        try {
            EnvironmentConfig config = parser.loadDefaultConfiguration();
            logger.info("Environment configuration loaded successfully");
            return config;
        } catch (Exception e) {
            logger.warn("Failed to load environment configuration: " + e.getMessage() + ", using empty configuration");
            return new EnvironmentConfig(java.util.Collections.emptyMap());
        }
    }
    
    /**
     * Ensures environment.yml exists in server root, generates default if missing
     */
    private void ensureEnvironmentConfiguration(EnvironmentConfigParser parser, ComponentLogger logger) {
        try {
            java.io.File environmentFile = new java.io.File("./environment.yml");
            
            if (!environmentFile.exists()) {
                logger.info("Environment configuration file not found, generating default configuration...");
                parser.generateDefaultEnvironmentFile(new java.io.File("."));
            }
        } catch (IOException e) {
            logger.warn("Failed to generate default environment configuration: " + e.getMessage());
        }
    }
    
    /**
     * Converts EnvironmentConfig to Map<String, Set<String>> for the new bootstrap-safe API
     */
    private Map<String, Set<String>> convertConfigToMap(EnvironmentConfig config) {
        // Simply return the underlying map as it's already in the correct format
        return new HashMap<>(config.getAllMappings());
    }
    
    /**
     * Logs a summary of which modules will be loaded for the selected configuration
     */
    private void logConfigurationSummary(ComponentLogger logger, EnvironmentConfig config, String selectedConfig) {
        var globalModules = config.getGlobalModules();
        var configModules = config.getModulesForEnvironment(selectedConfig);
        
        if (!globalModules.isEmpty()) {
            logger.info("Global modules to load: " + String.join(", ", globalModules));
        }
        
        if (!configModules.isEmpty()) {
            logger.info("Configuration '" + selectedConfig + "' modules to load: " + String.join(", ", configModules));
        }
        
        if (globalModules.isEmpty() && configModules.isEmpty()) {
            logger.warn("No modules configured for '" + selectedConfig + "'. All modules will be enabled by default.");
            logger.warn("Check environment.yml to configure modules for this server configuration.");
        }
    }
}