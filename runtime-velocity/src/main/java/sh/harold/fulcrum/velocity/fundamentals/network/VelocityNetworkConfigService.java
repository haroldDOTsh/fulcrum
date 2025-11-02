package sh.harold.fulcrum.velocity.fundamentals.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigResponseMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.network.RankVisualView;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class VelocityNetworkConfigService implements NetworkConfigService {
    private static final String ACTIVE_KEY = "network:config:active";
    private static final String REGISTRY_SERVER_ID = "registry-service";

    private final FulcrumVelocityPlugin plugin;
    private final Logger logger;
    private final MessageBus messageBus;
    private final LettuceSessionRedisClient redisClient;
    private final ObjectMapper mapper;
    private final NetworkProfileView fallbackProfile;

    private volatile NetworkProfileView activeProfile;

    VelocityNetworkConfigService(FulcrumVelocityPlugin plugin,
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

        logger.warn("Unable to fetch network configuration from Redis or registry; using bundled defaults");
        updateActiveProfile(fallbackProfile, "defaults");
    }

    private Optional<NetworkProfileView> loadFromRedis() {
        if (redisClient == null || !redisClient.isAvailable()) {
            return Optional.empty();
        }
        try {
            String activeJson = redisClient.get(ACTIVE_KEY);
            if (activeJson == null || activeJson.isBlank()) {
                return Optional.empty();
            }

            JsonNode node = mapper.readTree(activeJson);
            if (node.has("motd") || node.has("scoreboard") || node.has("ranks")) {
                NetworkProfileView profile = mapper.treeToValue(node, NetworkProfileView.class);
                return Optional.of(profile);
            }

            String profileId = node.path("profileId").asText(null);
            if (profileId == null || profileId.isBlank()) {
                return Optional.empty();
            }

            String legacyPayload = redisClient.get("network:config:" + profileId);
            if (legacyPayload == null || legacyPayload.isBlank()) {
                return Optional.empty();
            }
            NetworkProfileView legacyProfile = mapper.readValue(legacyPayload, NetworkProfileView.class);
            return Optional.of(legacyProfile);
        } catch (Exception ex) {
            logger.warn("Failed to read network configuration from Redis", ex);
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
                    logger.warn("Registry declined network configuration request: {}", response.getError());
                }
            } else {
                logger.warn("Unexpected response while fetching network configuration: {}", rawResponse);
            }
        } catch (Exception ex) {
            logger.warn("Failed to request network configuration from registry", ex);
        }
        return Optional.empty();
    }

    private void updateActiveProfile(NetworkProfileView profile, String source) {
        this.activeProfile = profile;
        logger.debug("Network configuration refreshed from {} (profile={})", source, profile.profileId());
    }

    private NetworkProfileView loadFallbackProfile() {
        try (InputStream input = plugin.getClass().getClassLoader()
                .getResourceAsStream("network-config-defaults.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing resource: network-config-defaults.json");
            }
            return mapper.readValue(input, NetworkProfileView.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load bundled network configuration defaults", ex);
        }
    }
}
