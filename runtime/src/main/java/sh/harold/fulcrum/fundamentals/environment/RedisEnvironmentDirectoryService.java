package sh.harold.fulcrum.fundamentals.environment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.environment.EnvironmentConfigParser;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDescriptorView;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryService;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryResponseMessage;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RedisEnvironmentDirectoryService implements EnvironmentDirectoryService {
    private static final String DIRECTORY_KEY = "network:environments:directory";

    private final JavaPlugin plugin;
    private final MessageBus messageBus;
    private final LettuceRedisOperations redisOperations;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final Map<String, EnvironmentDescriptorView> bundledDefaults;

    private volatile EnvironmentDirectoryView activeDirectory;

    RedisEnvironmentDirectoryService(JavaPlugin plugin,
                                     MessageBus messageBus,
                                     LettuceRedisOperations redisOperations,
                                     Logger logger) {
        this.plugin = plugin;
        this.messageBus = messageBus;
        this.redisOperations = redisOperations;
        this.logger = logger;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.bundledDefaults = loadBundledDefaults();
        this.activeDirectory = new EnvironmentDirectoryView(bundledDefaults, "defaults");
    }

    @Override
    public synchronized EnvironmentDirectoryView getDirectory() {
        if (activeDirectory == null) {
            refresh();
        }
        return activeDirectory;
    }

    @Override
    public synchronized void refresh() {
        Optional<EnvironmentDirectoryView> redisDirectory = loadFromRedis();
        if (redisDirectory.isPresent()) {
            updateActive(redisDirectory.get(), "redis");
            return;
        }

        Optional<EnvironmentDirectoryView> registryDirectory = requestFromRegistry();
        if (registryDirectory.isPresent()) {
            updateActive(registryDirectory.get(), "registry");
            return;
        }

        Optional<EnvironmentDirectoryView> fileDirectory = loadFromEnvironmentFile();
        if (fileDirectory.isPresent()) {
            updateActive(fileDirectory.get(), "environment.yml");
            logger.warning("Environment directory fallback to local environment.yml file");
            return;
        }

        updateActive(new EnvironmentDirectoryView(bundledDefaults, "defaults"), "defaults");
        logger.warning("Environment directory fallback to bundled defaults");
    }

    private Optional<EnvironmentDirectoryView> loadFromRedis() {
        if (redisOperations == null) {
            return Optional.empty();
        }
        try {
            String payload = redisOperations.get(DIRECTORY_KEY);
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            EnvironmentDirectoryView view = mapper.readValue(payload, EnvironmentDirectoryView.class);
            return Optional.of(view);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to read environment directory from Redis", ex);
            return Optional.empty();
        }
    }

    private Optional<EnvironmentDirectoryView> requestFromRegistry() {
        if (messageBus == null) {
            return Optional.empty();
        }

        try {
            UUID requestId = UUID.randomUUID();
            EnvironmentDirectoryRequestMessage request = new EnvironmentDirectoryRequestMessage(requestId, true);
            CompletableFuture<Object> future = messageBus.request(
                    "registry-service",
                    ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_REQUEST,
                    request,
                    Duration.ofSeconds(5)
            );

            Object response = future.get(5, TimeUnit.SECONDS);
            if (response instanceof EnvironmentDirectoryResponseMessage message) {
                if (message.isSuccess() && message.getDirectory() != null) {
                    return Optional.of(message.getDirectory());
                }
                if (message.getError() != null && !message.getError().isBlank()) {
                    logger.warning("Registry declined environment directory request: " + message.getError());
                }
            } else {
                logger.warning("Unexpected response while fetching environment directory: " + response);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to fetch environment directory from registry", ex);
        }
        return Optional.empty();
    }

    private Optional<EnvironmentDirectoryView> loadFromEnvironmentFile() {
        Path path = Path.of("./environment.yml");
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            EnvironmentConfigParser parser = new EnvironmentConfigParser();
            EnvironmentConfig config = parser.loadDefaultConfiguration();
            Map<String, EnvironmentDescriptorView> descriptors = new LinkedHashMap<>();
            config.getAllMappings().forEach((id, modules) -> descriptors.put(id,
                    new EnvironmentDescriptorView(id, id, List.copyOf(modules), "")));
            return Optional.of(new EnvironmentDirectoryView(descriptors, "environment.yml"));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to load environment.yml fallback", ex);
            return Optional.empty();
        }
    }

    private Map<String, EnvironmentDescriptorView> loadBundledDefaults() {
        try (InputStream input = plugin.getResource("environment-directory-defaults.json")) {
            if (input == null) {
                return Map.of();
            }
            return mapper.readValue(input, new TypeReference<Map<String, EnvironmentDescriptorView>>() {
            });
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to load bundled environment directory defaults", ex);
            return Map.of();
        }
    }

    private void updateActive(EnvironmentDirectoryView view, String source) {
        this.activeDirectory = view;
        logger.fine(() -> "Environment directory refreshed from " + source + " (revision=" + view.revision() + ")");
    }
}
