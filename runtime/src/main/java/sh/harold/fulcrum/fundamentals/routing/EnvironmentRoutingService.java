package sh.harold.fulcrum.fundamentals.routing;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.EnvironmentRouteRequestMessage;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade that lets modules and commands request player transfers to a specific
 * environment (e.g., lobby) via the registry.
 */
public final class EnvironmentRoutingService {
    private final JavaPlugin plugin;
    private final MessageBus messageBus;
    private final PlayerRouteRegistry routeRegistry;
    private final ServerLifecycleFeature lifecycleFeature;
    private final ServerIdentifier serverIdentifier;

    public EnvironmentRoutingService(JavaPlugin plugin,
                                     MessageBus messageBus,
                                     PlayerRouteRegistry routeRegistry,
                                     ServerLifecycleFeature lifecycleFeature,
                                     ServerIdentifier serverIdentifier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.routeRegistry = routeRegistry;
        this.lifecycleFeature = lifecycleFeature;
        this.serverIdentifier = Objects.requireNonNull(serverIdentifier, "serverIdentifier");
    }

    private static String normalize(String environmentId) {
        return environmentId != null ? environmentId.trim().toLowerCase(Locale.ROOT) : "";
    }

    public EnvironmentRouteResult routePlayer(Player player, String environmentId, RouteOptions options) {
        Objects.requireNonNull(player, "player");
        environmentId = normalize(environmentId);
        if (environmentId.isBlank()) {
            return EnvironmentRouteResult.failure("Environment id missing");
        }

        RouteOptions effective = options != null ? options : RouteOptions.builder().build();
        Location targetLocation = resolveLocation(player, effective);
        String proxyId = resolveProxyId(player.getUniqueId());
        if (proxyId == null || proxyId.isBlank()) {
            return EnvironmentRouteResult.failure("No proxy id associated with player");
        }

        if (!isEnvironmentAvailable(environmentId, effective.targetServerId())) {
            switch (effective.failureMode()) {
                case FAIL_WITH_KICK -> {
                    // TODO: Replace with limbo transfer when implemented.
                    plugin.getLogger().warning(() -> "Environment '" + environmentId + "' unavailable; kicking "
                            + player.getName() + " until limbo support is implemented.");
                    player.kickPlayer("No available servers for " + environmentId + ". Please try again.");
                }
                case REPORT_ONLY -> {
                    // Command handlers will surface the message themselves.
                }
            }
            return EnvironmentRouteResult.failure("No available servers for environment '" + environmentId + "'");
        }

        EnvironmentRouteRequestMessage message = buildMessage(player, proxyId, environmentId, targetLocation, effective);
        messageBus.broadcast(ChannelConstants.REGISTRY_ENVIRONMENT_ROUTE_REQUEST, message);
        return EnvironmentRouteResult.success(message.getRequestId());
    }

    public EnvironmentRouteResult routePlayers(Collection<? extends Player> players,
                                               String environmentId,
                                               RouteOptions options) {
        if (players == null || players.isEmpty()) {
            return EnvironmentRouteResult.failure("No players to route");
        }
        Map<UUID, EnvironmentRouteResult> results = new ConcurrentHashMap<>();
        players.forEach(player -> results.put(player.getUniqueId(), routePlayer(player, environmentId, options)));
        boolean allSuccess = results.values().stream().allMatch(EnvironmentRouteResult::success);
        return allSuccess
                ? EnvironmentRouteResult.success(null)
                : EnvironmentRouteResult.partial(results);
    }

    private boolean isEnvironmentAvailable(String environmentId, String targetServerId) {
        if (targetServerId != null && targetServerId.equalsIgnoreCase(serverIdentifier.getServerId())) {
            return true;
        }
        if (lifecycleFeature == null) {
            return true; // assume available; registry will perform final validation
        }
        if (targetServerId != null && !targetServerId.isBlank()) {
            return lifecycleFeature.isServerKnown(targetServerId);
        }
        return lifecycleFeature.hasServerForRole(environmentId);
    }

    private EnvironmentRouteRequestMessage buildMessage(Player player,
                                                        String proxyId,
                                                        String environmentId,
                                                        Location location,
                                                        RouteOptions options) {
        EnvironmentRouteRequestMessage message = new EnvironmentRouteRequestMessage();
        message.setPlayerId(player.getUniqueId());
        message.setPlayerName(player.getName());
        message.setProxyId(proxyId);
        message.setOriginServerId(serverIdentifier.getServerId());
        message.setTargetEnvironmentId(environmentId);
        if (options.targetServerId() != null && !options.targetServerId().isBlank()) {
            message.setTargetServerId(options.targetServerId());
        }
        if (options.failureMode() == RouteOptions.FailureMode.REPORT_ONLY) {
            message.setFailureMode(EnvironmentRouteRequestMessage.FailureMode.REPORT_ONLY);
        }

        if (location != null) {
            message.setWorldName(location.getWorld() != null ? location.getWorld().getName() : "");
            message.setSpawnX(location.getX());
            message.setSpawnY(location.getY());
            message.setSpawnZ(location.getZ());
            message.setSpawnYaw(location.getYaw());
            message.setSpawnPitch(location.getPitch());
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("environment", environmentId);
        metadata.put("originServer", serverIdentifier.getServerId());
        if (options.reason() != null && !options.reason().isBlank()) {
            metadata.put("reason", options.reason());
        }
        if (!options.metadata().isEmpty()) {
            metadata.putAll(options.metadata());
        }
        message.setMetadata(metadata);
        return message;
    }

    private Location resolveLocation(Player player, RouteOptions options) {
        if (options.targetLocation() != null) {
            return options.targetLocation();
        }
        Location current = player.getLocation();
        return current != null ? current.clone() : null;
    }

    private String resolveProxyId(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        if (routeRegistry != null) {
            return routeRegistry.get(playerId)
                    .map(PlayerRouteRegistry.RouteAssignment::proxyId)
                    .filter(id -> id != null && !id.isBlank())
                    .orElseGet(this::fallbackProxyId);
        }
        return fallbackProxyId();
    }

    private String fallbackProxyId() {
        if (lifecycleFeature == null) {
            return null;
        }
        return lifecycleFeature.getCurrentProxyId().orElse(null);
    }

    public record EnvironmentRouteResult(boolean success,
                                         String message,
                                         UUID requestId,
                                         Map<UUID, EnvironmentRouteResult> partial) {
        public static EnvironmentRouteResult success(UUID requestId) {
            return new EnvironmentRouteResult(true, null, requestId, Map.of());
        }

        public static EnvironmentRouteResult failure(String message) {
            return new EnvironmentRouteResult(false, message, null, Map.of());
        }

        public static EnvironmentRouteResult partial(Map<UUID, EnvironmentRouteResult> results) {
            return new EnvironmentRouteResult(false, "Partial success", null, Map.copyOf(results));
        }
    }

    public record RouteOptions(Location targetLocation,
                               String targetServerId,
                               FailureMode failureMode,
                               String reason,
                               Map<String, String> metadata) {
        public static Builder builder() {
            return new Builder();
        }

        public enum FailureMode {
            FAIL_WITH_KICK,
            REPORT_ONLY
        }

        public static final class Builder {
            private final Map<String, String> metadata = new LinkedHashMap<>();
            private Location targetLocation;
            private String targetServerId;
            private FailureMode failureMode = FailureMode.FAIL_WITH_KICK;
            private String reason;

            public Builder targetLocation(Location targetLocation) {
                this.targetLocation = targetLocation;
                return this;
            }

            public Builder targetServerId(String targetServerId) {
                this.targetServerId = targetServerId;
                return this;
            }

            public Builder failureMode(FailureMode failureMode) {
                if (failureMode != null) {
                    this.failureMode = failureMode;
                }
                return this;
            }

            public Builder reason(String reason) {
                this.reason = reason;
                return this;
            }

            public Builder metadata(String key, String value) {
                if (key != null && value != null) {
                    metadata.put(key, value);
                }
                return this;
            }

            public Builder metadata(Map<String, String> additional) {
                if (additional != null) {
                    additional.forEach(this::metadata);
                }
                return this;
            }

            public RouteOptions build() {
                return new RouteOptions(targetLocation, targetServerId, failureMode, reason, Map.copyOf(metadata));
            }
        }
    }
}
