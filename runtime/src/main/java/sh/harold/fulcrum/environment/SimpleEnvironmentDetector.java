package sh.harold.fulcrum.environment;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple environment detection that reads the current environment from an ENVIRONMENT file
 * in the plugin's root directory. This replaces the complex environment.yml system.
 */
public class SimpleEnvironmentDetector {
    
    private static final String ENVIRONMENT_FILE = "ENVIRONMENT";
    private static final String DEFAULT_ENVIRONMENT = "dev";
    
    private final Plugin plugin;
    private final Logger logger;
    private String currentEnvironment;
    
    /**
     * Static method to detect the current environment without needing an instance
     * @return the current environment name
     */
    public static String detectEnvironment() {
        File serverRoot = new File(".");
        Path environmentFile = serverRoot.toPath().resolve(ENVIRONMENT_FILE);
        
        try {
            if (Files.exists(environmentFile)) {
                String environment = Files.readString(environmentFile).trim();
                if (!environment.isEmpty()) {
                    return environment;
                }
            }
        } catch (IOException e) {
            // Silent fail - return default
        }
        
        return DEFAULT_ENVIRONMENT;
    }
    
    public SimpleEnvironmentDetector(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.currentEnvironment = loadEnvironment();
    }
    
    /**
     * Gets the current environment name
     * @return the current environment (e.g., "dev", "lobby", "bedwars")
     */
    public String getCurrentEnvironment() {
        return currentEnvironment;
    }
    
    /**
     * Checks if the current environment matches any of the required environments
     * @param requiredEnvironments array of required environment names
     * @return true if current environment is in the required list, false otherwise
     */
    public boolean isRequiredInCurrentEnvironment(String[] requiredEnvironments) {
        if (requiredEnvironments == null || requiredEnvironments.length == 0) {
            // If no specific environments required, module loads in all environments
            return true;
        }
        
        for (String required : requiredEnvironments) {
            if (currentEnvironment.equalsIgnoreCase(required.trim())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Loads the environment from the ENVIRONMENT file
     * @return the environment name, or DEFAULT_ENVIRONMENT if file doesn't exist or is invalid
     */
    private String loadEnvironment() {
        // Use the same location as the old EnvironmentSelector - server root directory
        File serverRoot = new File(".");
        Path environmentFile = serverRoot.toPath().resolve(ENVIRONMENT_FILE);
        
        try {
            if (Files.exists(environmentFile)) {
                String environment = Files.readString(environmentFile).trim();
                if (!environment.isEmpty()) {
                    logger.info("Loaded environment: " + environment);
                    return environment;
                } else {
                    logger.warning("ENVIRONMENT file is empty, using default: " + DEFAULT_ENVIRONMENT);
                }
            } else {
                // Don't create the file in server root - admin should create it
                logger.info("ENVIRONMENT file not found in server root, using default: " + DEFAULT_ENVIRONMENT);
                logger.info("To set environment, create an ENVIRONMENT file in the server root directory");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read ENVIRONMENT file, using default: " + DEFAULT_ENVIRONMENT, e);
        }
        
        return DEFAULT_ENVIRONMENT;
    }
    
    /**
     * Reloads the environment from the file (useful for runtime changes)
     */
    public void reload() {
        String oldEnvironment = currentEnvironment;
        currentEnvironment = loadEnvironment();
        if (!oldEnvironment.equals(currentEnvironment)) {
            logger.info("Environment changed from " + oldEnvironment + " to " + currentEnvironment);
        }
    }
}