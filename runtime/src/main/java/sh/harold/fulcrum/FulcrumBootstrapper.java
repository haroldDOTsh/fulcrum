package sh.harold.fulcrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.environment.EnvironmentFileReader;
import sh.harold.fulcrum.environment.EnvironmentFileSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bootstrap class for Fulcrum that initializes the environment configuration using the registry-managed directory.
 */
@SuppressWarnings("UnstableApiUsage")
public class FulcrumBootstrapper implements PluginBootstrap {
    private static final String ENVIRONMENT_FILE = "ENVIRONMENT";
    private static final String DEFAULT_ENVIRONMENT = "dev";
    private static final String DIRECTORY_KEY = "network:environments:directory";
    private static final int DIRECTORY_MAX_ATTEMPTS = 6;
    private static final Duration DIRECTORY_RETRY_BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration DIRECTORY_RETRY_MAX_DELAY = Duration.ofSeconds(10);

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        ComponentLogger logger = context.getLogger();

        String environment = detectEnvironment(logger);
        logger.info("Detected environment: " + environment);

        EnvironmentConfig config = loadEnvironmentConfiguration(logger);
        if (!config.getAllMappings().containsKey(environment)) {
            throw new IllegalStateException("Environment '" + environment + "' not defined in the registry directory");
        }

        Map<String, Set<String>> configMap = new HashMap<>(config.getAllMappings());

        try {
            FulcrumEnvironment.initialize(environment, configMap);
            logger.info("Fulcrum environment initialized with configuration: " + environment);
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

    private String detectEnvironment(ComponentLogger logger) {
        Path serverRoot = Path.of(".");
        Path environmentFile = serverRoot.resolve(ENVIRONMENT_FILE);

        if (Files.exists(environmentFile)) {
            try {
                var environmentSelection = EnvironmentFileReader.read(serverRoot);
                if (environmentSelection.isPresent()) {
                    EnvironmentFileSettings settings = environmentSelection.get();
                    logger.info("Server configuration selected from ENVIRONMENT file: " + settings.role());
                    settings.ipOverride().ifPresent(ip ->
                            logger.info("ENVIRONMENT IP override (unused during bootstrap) detected: " + ip));
                    return settings.role();
                }
                logger.warn("ENVIRONMENT file is empty, using default configuration.");
            } catch (IOException e) {
                logger.warn("Failed to read ENVIRONMENT file: " + e.getMessage());
            }
        } else {
            logger.info("No ENVIRONMENT file found, using default configuration: " + DEFAULT_ENVIRONMENT);
            logger.info("Create an ENVIRONMENT file containing a configuration name from the registry directory to select a specific server configuration");
            return DEFAULT_ENVIRONMENT;
        }

        logger.info("Using default configuration: " + DEFAULT_ENVIRONMENT);
        logger.info("Create an ENVIRONMENT file containing a configuration name from the registry directory to select a specific server configuration");
        return DEFAULT_ENVIRONMENT;
    }

    private EnvironmentConfig loadEnvironmentConfiguration(ComponentLogger logger) {
        Map<String, Set<String>> mappings = loadEnvironmentDirectory(logger);
        if (mappings.isEmpty()) {
            throw new IllegalStateException("Registry environment directory does not define any environments");
        }
        return new EnvironmentConfig(mappings);
    }

    private Map<String, Set<String>> loadEnvironmentDirectory(ComponentLogger logger) {
        BootstrapRedisConfig redisConfig = loadRedisConfig(logger);

        Duration delay = DIRECTORY_RETRY_BASE_DELAY;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= DIRECTORY_MAX_ATTEMPTS; attempt++) {
            try {
                return readEnvironmentDirectory(redisConfig);
            } catch (Exception ex) {
                lastFailure = ex instanceof IllegalStateException ? ex : new IllegalStateException(ex);
                if (attempt == DIRECTORY_MAX_ATTEMPTS) {
                    break;
                }
                logger.warn("Unable to load environment directory from Redis (attempt "
                        + attempt + "/" + DIRECTORY_MAX_ATTEMPTS + "); retrying in "
                        + delay.toSeconds() + " seconds");
                sleep(delay);
                delay = delay.multipliedBy(2);
                if (delay.compareTo(DIRECTORY_RETRY_MAX_DELAY) > 0) {
                    delay = DIRECTORY_RETRY_MAX_DELAY;
                }
            }
        }

        logger.error("Failed to load environment directory from Redis after "
                + DIRECTORY_MAX_ATTEMPTS + " attempts");
        throw new IllegalStateException("Unable to load environment directory", lastFailure);
    }

    private Map<String, Set<String>> readEnvironmentDirectory(BootstrapRedisConfig redisConfig) throws IOException {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redisConfig.host())
                .withPort(redisConfig.port());
        if (!redisConfig.password().isBlank()) {
            builder.withPassword(redisConfig.password().toCharArray());
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try (RedisClient client = RedisClient.create(builder.build());
             StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            String payload = commands.get(DIRECTORY_KEY);
            if (payload == null || payload.isBlank()) {
                throw new IllegalStateException("Environment directory key missing from Redis");
            }

            EnvironmentDirectoryView view = mapper.readValue(payload, EnvironmentDirectoryView.class);
            Map<String, Set<String>> mappings = new HashMap<>();
            view.environments().forEach((id, descriptor) ->
                    mappings.put(id, new LinkedHashSet<>(descriptor.modules())));
            return mappings;
        }
    }

    private BootstrapRedisConfig loadRedisConfig(ComponentLogger logger) {
        try {
            Path dataDir = Path.of("plugins", "Fulcrum");
            Files.createDirectories(dataDir);
            Path configPath = dataDir.resolve("database-config.yml");

            if (Files.notExists(configPath)) {
                try (InputStream input = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
                    if (input == null) {
                        throw new IllegalStateException("Missing bundled database-config.yml resource");
                    }
                    Files.copy(input, configPath);
                }
            }

            Yaml yaml = new Yaml();
            try (InputStream input = Files.newInputStream(configPath, StandardOpenOption.READ)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = yaml.load(input);
                if (root == null) {
                    throw new IllegalStateException("database-config.yml is empty");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> redis = (Map<String, Object>) root.get("redis");
                if (redis == null) {
                    throw new IllegalStateException("Redis configuration missing from database-config.yml");
                }
                String host = String.valueOf(redis.getOrDefault("host", "localhost"));
                Object portObj = redis.getOrDefault("port", 6379);
                int port = portObj instanceof Number ? ((Number) portObj).intValue() : Integer.parseInt(String.valueOf(portObj));
                String password = String.valueOf(redis.getOrDefault("password", ""));
                return new BootstrapRedisConfig(host, port, password == null ? "" : password);
            }
        } catch (Exception ex) {
            logger.error("Failed to load Redis configuration: " + ex.getMessage());
            throw new IllegalStateException("Unable to load Redis configuration", ex);
        }
    }

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
        }
    }

    private void sleep(Duration duration) {
        long millis = duration.toMillis();
        int nanos = (int) (duration.toNanos() % 1_000_000);
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for environment directory availability", e);
        }
    }

    private record BootstrapRedisConfig(String host, int port, String password) {
    }
}
