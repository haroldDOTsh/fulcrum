package sh.harold.fulcrum;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.environment.EnvironmentConfig;
import sh.harold.fulcrum.environment.EnvironmentConfigParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bootstrap class for Fulcrum that initializes the environment
 * detection system and environment.yml configuration before any plugins are loaded.
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
            // Initialize FulcrumEnvironment with the bootstrap-safe configuration
            FulcrumEnvironment.initialize(environment, configMap);
            logger.info("Fulcrum environment initialized: " + environment);
            
            // Log configuration summary
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
     * Detects the current environment from the ENVIRONMENT file
     */
    private String detectEnvironment(ComponentLogger logger) {
        Path serverRoot = Path.of(".");
        Path environmentFile = serverRoot.resolve(ENVIRONMENT_FILE);
        
        try {
            if (Files.exists(environmentFile)) {
                String environment = Files.readString(environmentFile).trim();
                if (!environment.isEmpty()) {
                    return environment;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read ENVIRONMENT file: " + e.getMessage());
        }
        
        logger.info("Using default environment: " + DEFAULT_ENVIRONMENT);
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
     * Logs a summary of the loaded configuration
     */
    private void logConfigurationSummary(ComponentLogger logger, EnvironmentConfig config, String currentEnvironment) {
        var globalModules = config.getGlobalModules();
        var envModules = config.getModulesForEnvironment(currentEnvironment);
        
        if (!globalModules.isEmpty()) {
            logger.info("Global modules: " + String.join(", ", globalModules));
        }
        
        if (!envModules.isEmpty()) {
            logger.info("Environment '" + currentEnvironment + "' modules: " + String.join(", ", envModules));
        }
        
        if (globalModules.isEmpty() && envModules.isEmpty()) {
            logger.warn("No modules configured for environment '" + currentEnvironment + "'. All modules will be enabled by default.");
        }
    }
}