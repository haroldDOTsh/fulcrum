package sh.harold.fulcrum.velocity.fundamentals.environment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDescriptorView;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryService;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryResponseMessage;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class VelocityEnvironmentDirectoryService implements EnvironmentDirectoryService {
    private static final String DIRECTORY_KEY = "network:environments:directory";
    private static final String REGISTRY_SERVER_ID = "registry-service";

    private final FulcrumVelocityPlugin plugin;
    private final Logger logger;
    private final MessageBus messageBus;
    private final LettuceSessionRedisClient redisClient;
    private final ObjectMapper mapper;
    private final Map<String, EnvironmentDescriptorView> fallback;

    private volatile EnvironmentDirectoryView activeDirectory;

    VelocityEnvironmentDirectoryService(FulcrumVelocityPlugin plugin,
                                        Logger logger,
                                        MessageBus messageBus,
                                        LettuceSessionRedisClient redisClient) {
        this.plugin = plugin;
        this.logger = logger;
        this.messageBus = messageBus;
        this.redisClient = redisClient;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.fallback = loadBundledDefaults();
        this.activeDirectory = new EnvironmentDirectoryView(fallback, "defaults");
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
        Optional<EnvironmentDirectoryView> redisView = loadFromRedis();
        if (redisView.isPresent()) {
            updateDirectory(redisView.get(), "redis");
            return;
        }

        Optional<EnvironmentDirectoryView> registryView = requestFromRegistry();
        if (registryView.isPresent()) {
            updateDirectory(registryView.get(), "registry");
            return;
        }

        logger.warn("Environment directory unavailable from registry; using bundled defaults");
        updateDirectory(new EnvironmentDirectoryView(fallback, "defaults"), "defaults");
    }

    private Optional<EnvironmentDirectoryView> loadFromRedis() {
        if (redisClient == null || !redisClient.isAvailable()) {
            return Optional.empty();
        }
        try {
            String payload = redisClient.get(DIRECTORY_KEY);
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            EnvironmentDirectoryView view = mapper.readValue(payload, EnvironmentDirectoryView.class);
            return Optional.of(view);
        } catch (Exception ex) {
            logger.warn("Failed to read environment directory from Redis", ex);
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
                    REGISTRY_SERVER_ID,
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
                    logger.warn("Registry declined environment directory request: {}", message.getError());
                }
            } else {
                logger.warn("Unexpected response while fetching environment directory: {}", response);
            }
        } catch (Exception ex) {
            logger.warn("Failed to request environment directory from registry", ex);
        }
        return Optional.empty();
    }

    private Map<String, EnvironmentDescriptorView> loadBundledDefaults() {
        try (InputStream input = plugin.getClass().getClassLoader()
                .getResourceAsStream("environment-directory-defaults.json")) {
            if (input == null) {
                logger.warn("Missing resource: environment-directory-defaults.json");
                return Map.of();
            }
            return mapper.readValue(input, new TypeReference<Map<String, EnvironmentDescriptorView>>() {
            });
        } catch (Exception ex) {
            logger.error("Failed to load bundled environment directory defaults", ex);
            return Map.of();
        }
    }

    private void updateDirectory(EnvironmentDirectoryView view, String source) {
        this.activeDirectory = view;
        logger.debug("Environment directory refreshed from {} (revision={})", source, view.revision());
    }
}
