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

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RedisNetworkConfigService implements NetworkConfigService {
    private static final String ACTIVE_KEY = "network:config:active";
    private static final String REGISTRY_SERVER_ID = "registry-service";
    private static final int INITIAL_MAX_ATTEMPTS = 6;
    private static final Duration INITIAL_RETRY_BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration INITIAL_RETRY_MAX_DELAY = Duration.ofSeconds(10);

    private final JavaPlugin plugin;
    private final MessageBus messageBus;
    private final LettuceRedisOperations redisOperations;
    private final Logger logger;
    private final ObjectMapper mapper;
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

        this.activeProfile = null;
        ensureInitialProfileLoaded();
    }

    @Override
    public synchronized NetworkProfileView getActiveProfile() {
        if (activeProfile == null) {
            throw new IllegalStateException("Network configuration unavailable");
        }
        return activeProfile;
    }

    @Override
    public Optional<RankVisualView> getRankVisual(String rankId) {
        return getActiveProfile().getRankVisual(rankId);
    }

    @Override
    public synchronized void refreshActiveProfile() {
        if (tryRefreshFromRemote()) {
            return;
        }

        logger.warning("Unable to refresh network configuration from Redis or registry; retaining last known profile");
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
            if (node.has("motd") || node.has("scoreboard") || node.has("ranks")) {
                NetworkProfileView profile = mapper.treeToValue(node, NetworkProfileView.class);
                return Optional.of(profile);
            }

            String profileId = node.path("profileId").asText(null);
            if (profileId == null || profileId.isBlank()) {
                return Optional.empty();
            }

            String legacyPayload = redisOperations.get("network:config:" + profileId);
            if (legacyPayload == null || legacyPayload.isBlank()) {
                return Optional.empty();
            }
            NetworkProfileView legacyProfile = mapper.readValue(legacyPayload, NetworkProfileView.class);
            return Optional.of(legacyProfile);
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

    private boolean tryRefreshFromRemote() {
        Optional<NetworkProfileView> redisProfile = loadFromRedis();
        if (redisProfile.isPresent()) {
            updateActiveProfile(redisProfile.get(), "redis");
            return true;
        }

        Optional<NetworkProfileView> registryProfile = requestFromRegistry(true);
        if (registryProfile.isPresent()) {
            updateActiveProfile(registryProfile.get(), "registry");
            return true;
        }

        return false;
    }

    private void updateActiveProfile(NetworkProfileView profile, String source) {
        this.activeProfile = profile;
        logger.fine(() -> "Network configuration refreshed from " + source + " (profile=" + profile.profileId() + ")");
    }

    private void ensureInitialProfileLoaded() {
        Duration delay = INITIAL_RETRY_BASE_DELAY;
        for (int attempt = 1; attempt <= INITIAL_MAX_ATTEMPTS; attempt++) {
            if (tryRefreshFromRemote()) {
                logger.info("Network configuration loaded after " + attempt + " attempt(s)");
                return;
            }

            if (attempt == INITIAL_MAX_ATTEMPTS) {
                break;
            }

            logger.warning("Unable to reach Redis or registry for network configuration (attempt "
                    + attempt + "/" + INITIAL_MAX_ATTEMPTS + "); retrying in "
                    + delay.toSeconds() + " seconds");
            sleep(delay);
            delay = delay.multipliedBy(2);
            if (delay.compareTo(INITIAL_RETRY_MAX_DELAY) > 0) {
                delay = INITIAL_RETRY_MAX_DELAY;
            }
        }

        throw new IllegalStateException("Unable to load network configuration from Redis or registry after "
                + INITIAL_MAX_ATTEMPTS + " attempts");
    }

    private void sleep(Duration duration) {
        long millis = duration.toMillis();
        int nanos = (int) (duration.toNanos() % 1_000_000);
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for network configuration availability", ex);
        }
    }
}
