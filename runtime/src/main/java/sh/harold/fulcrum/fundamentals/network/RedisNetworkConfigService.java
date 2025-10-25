package sh.harold.fulcrum.fundamentals.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigResponseMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.network.RankVisualView;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RedisNetworkConfigService implements NetworkConfigService {
    private static final String ACTIVE_KEY = "network:config:active";
    private static final String PROFILE_KEY_PREFIX = "network:config:";
    private static final String REGISTRY_SERVER_ID = "registry-service";

    private final JavaPlugin plugin;
    private final MessageBus messageBus;
    private final LettuceRedisOperations redisOperations;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final NetworkProfileView fallbackProfile;

    private volatile NetworkProfileView activeProfile;

    RedisNetworkConfigService(JavaPlugin plugin,
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

        this.fallbackProfile = loadFallbackProfile();
        this.activeProfile = fallbackProfile;
    }

    @Override
    public synchronized NetworkProfileView getActiveProfile() {
        if (activeProfile == null) {
            refreshActiveProfile();
        }
        return activeProfile != null ? activeProfile : fallbackProfile;
    }

    @Override
    public Optional<RankVisualView> getRankVisual(String rankId) {
        return getActiveProfile().getRankVisual(rankId);
    }

    @Override
    public synchronized void refreshActiveProfile() {
        Optional<NetworkProfileView> redisProfile = loadFromRedis();
        if (redisProfile.isPresent()) {
            updateActiveProfile(redisProfile.get(), "redis");
            return;
        }

        Optional<NetworkProfileView> registryProfile = requestFromRegistry(true);
        if (registryProfile.isPresent()) {
            updateActiveProfile(registryProfile.get(), "registry");
            return;
        }

        logger.warning("Unable to load network configuration from Redis or registry; falling back to bundled defaults");
        updateActiveProfile(fallbackProfile, "defaults");
    }

    private Optional<NetworkProfileView> loadFromRedis() {
        if (redisOperations == null) {
            return Optional.empty();
        }
        try {
            String activeJson = redisOperations.get(ACTIVE_KEY);
            if (activeJson == null || activeJson.isBlank()) {
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(activeJson);
            String profileId = node.path("profileId").asText(null);
            if (profileId == null || profileId.isBlank()) {
                return Optional.empty();
            }
            String payloadJson = redisOperations.get(PROFILE_KEY_PREFIX + profileId);
            if (payloadJson == null || payloadJson.isBlank()) {
                return Optional.empty();
            }
            NetworkProfileView profile = mapper.readValue(payloadJson, NetworkProfileView.class);
            return Optional.of(profile);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to read network profile from Redis", ex);
            return Optional.empty();
        }
    }

    private Optional<NetworkProfileView> requestFromRegistry(boolean refresh) {
        if (messageBus == null) {
            return Optional.empty();
        }

        UUID requestId = UUID.randomUUID();
        NetworkConfigRequestMessage request = new NetworkConfigRequestMessage(requestId, null, refresh);

        try {
            CompletableFuture<Object> future = messageBus.request(
                    REGISTRY_SERVER_ID,
                    ChannelConstants.REGISTRY_NETWORK_CONFIG_REQUEST,
                    request,
                    Duration.ofSeconds(5)
            );

            Object rawResponse = future.get(5, TimeUnit.SECONDS);
            if (rawResponse instanceof NetworkConfigResponseMessage response) {
                if (response.isSuccess() && response.getProfile() != null) {
                    return Optional.of(response.getProfile());
                }
                if (response.getError() != null && !response.getError().isBlank()) {
                    logger.warning("Registry declined network config request: " + response.getError());
                }
            } else {
                logger.warning("Unexpected response object while fetching network config: " + rawResponse);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to fetch network profile from registry", ex);
        }
        return Optional.empty();
    }

    private void updateActiveProfile(NetworkProfileView profile, String source) {
        this.activeProfile = profile;
        logger.fine(() -> "Network configuration refreshed from " + source + " (profile=" + profile.profileId() + ")");
    }

    private NetworkProfileView loadFallbackProfile() {
        try (InputStream inputStream = plugin.getResource("network-config-defaults.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing resource: network-config-defaults.json");
            }
            return mapper.readValue(inputStream, NetworkProfileView.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load bundled network configuration defaults", ex);
        }
    }
}
