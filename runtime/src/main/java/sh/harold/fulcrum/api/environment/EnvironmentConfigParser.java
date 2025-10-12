package sh.harold.fulcrum.api.environment;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Parser for environment.yml configuration files.
 * Uses the existing YamlConfigLoader infrastructure for consistent YAML handling.
 *
 * @since 1.2.0
 */
public class EnvironmentConfigParser {
    private static final Logger LOGGER = Logger.getLogger(EnvironmentConfigParser.class.getName());
    private final Yaml yaml;

    public EnvironmentConfigParser() {
        this.yaml = new Yaml();
    }

    /**
     * Loads environment configuration from environment.yml file
     *
     * @param configPath path to the environment.yml file
     * @return parsed configuration, or empty config if file doesn't exist
     */
    public EnvironmentConfig loadConfiguration(String configPath) {
        Path path = Path.of(configPath);

        if (!Files.exists(path)) {
            LOGGER.info("Environment configuration file not found: " + configPath + ", using empty configuration");
            return createEmptyConfig();
        }

        try (InputStream inputStream = new FileInputStream(configPath)) {
            Map<String, Object> yamlData = yaml.load(inputStream);
            return parseEnvironmentConfig(yamlData);
        } catch (IOException e) {
            LOGGER.warning("Failed to read environment configuration from: " + configPath + ", error: " + e.getMessage());
            return createEmptyConfig();
        } catch (Exception e) {
            LOGGER.warning("Failed to parse environment configuration: " + e.getMessage());
            return createEmptyConfig();
        }
    }

    /**
     * Loads environment configuration from the default location (./environment.yml)
     *
     * @return parsed configuration
     */
    public EnvironmentConfig loadDefaultConfiguration() {
        return loadConfiguration("./environment.yml");
    }

    /**
     * Writes an EnvironmentConfig to a YAML file.
     *
     * @param config     The EnvironmentConfig to write
     * @param configFile The file to write to
     * @throws IOException If writing fails
     */
    public void writeEnvironmentConfig(EnvironmentConfig config, File configFile) throws IOException {
        // Convert EnvironmentConfig back to Map format for YAML serialization
        Map<String, List<String>> configData = new LinkedHashMap<>();

        // Get all mappings and convert Set<String> to List<String> for YAML
        for (Map.Entry<String, Set<String>> entry : config.getAllMappings().entrySet()) {
            configData.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        try (FileOutputStream outputStream = new FileOutputStream(configFile);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            yaml.dump(configData, writer);
            LOGGER.info("Environment config written to: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.severe("Failed to write environment config to " + configFile.getAbsolutePath() + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Creates a default environment configuration with 'global' and 'dev' environments.
     *
     * @return A default EnvironmentConfig
     */
    public EnvironmentConfig createDefaultConfig() {
        Map<String, Set<String>> defaultEnvironments = new LinkedHashMap<>();
        defaultEnvironments.put("global", new LinkedHashSet<>(Arrays.asList("something")));
        defaultEnvironments.put("dev", new LinkedHashSet<>(Arrays.asList("something")));

        return new EnvironmentConfig(defaultEnvironments);
    }

    /**
     * Generates a default environment.yml file in the specified directory.
     *
     * @param serverRootDir The server root directory where the file should be created
     * @throws IOException If file creation fails
     */
    public void generateDefaultEnvironmentFile(File serverRootDir) throws IOException {
        File environmentFile = new File(serverRootDir, "environment.yml");

        if (environmentFile.exists()) {
            LOGGER.info("Environment config file already exists: " + environmentFile.getAbsolutePath());
            return;
        }

        EnvironmentConfig defaultConfig = createDefaultConfig();
        writeEnvironmentConfig(defaultConfig, environmentFile);

        LOGGER.info("Generated default environment.yml at: " + environmentFile.getAbsolutePath());
    }

    /**
     * Parses environment configuration from YAML data
     *
     * @param yamlData the parsed YAML data
     * @return environment configuration
     */
    private EnvironmentConfig parseEnvironmentConfig(Map<String, Object> yamlData) {
        if (yamlData == null) {
            return createEmptyConfig();
        }

        Map<String, Set<String>> environmentModules = new HashMap<>();

        for (Map.Entry<String, Object> entry : yamlData.entrySet()) {
            String environmentName = entry.getKey();
            Object moduleList = entry.getValue();

            Set<String> modules = parseModuleList(moduleList, environmentName);
            if (!modules.isEmpty()) {
                environmentModules.put(environmentName, modules);
            }
        }

        LOGGER.info("Loaded environment configuration with " + environmentModules.size() + " environments");
        for (Map.Entry<String, Set<String>> entry : environmentModules.entrySet()) {
            LOGGER.info("  " + entry.getKey() + ": " + entry.getValue().size() + " modules");
        }

        return new EnvironmentConfig(environmentModules);
    }

    /**
     * Parses a module list from YAML data
     *
     * @param moduleList      the module list object from YAML
     * @param environmentName the environment name (for logging)
     * @return set of module names
     */
    @SuppressWarnings("unchecked")
    private Set<String> parseModuleList(Object moduleList, String environmentName) {
        if (moduleList == null) {
            return Collections.emptySet();
        }

        if (moduleList instanceof List) {
            Set<String> modules = new HashSet<>();
            List<Object> list = (List<Object>) moduleList;

            for (Object item : list) {
                if (item instanceof String) {
                    String moduleName = ((String) item).trim();
                    if (!moduleName.isEmpty()) {
                        modules.add(moduleName);
                    }
                } else {
                    LOGGER.warning("Invalid module entry in environment '" + environmentName + "': " + item);
                }
            }

            return modules;
        } else {
            LOGGER.warning("Invalid module list format for environment '" + environmentName + "': expected list, got " + moduleList.getClass().getSimpleName());
            return Collections.emptySet();
        }
    }

    /**
     * Creates an empty configuration with no environments or modules
     *
     * @return empty environment configuration
     */
    private EnvironmentConfig createEmptyConfig() {
        return new EnvironmentConfig(Collections.emptyMap());
    }
}