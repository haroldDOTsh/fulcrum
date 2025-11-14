package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteCommand;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.api.ProxyIdentifier;
import sh.harold.fulcrum.velocity.api.ServerIdentifier;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ProxyConnectionHandler {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration LOBBY_ROUTE_TIMEOUT = Duration.ofSeconds(5);
    private static final ChannelIdentifier ROUTE_CHANNEL = MinecraftChannelIdentifier.from("fulcrum:route");

    private final ProxyServer proxy;
    private final Logger logger;
    private final VelocityServerLifecycleFeature lifecycleFeature;
    private final ServiceLocator serviceLocator;
    // Cache server metrics for optimal selection
    private final Map<String, ServerMetrics> serverMetricsCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRouteCommand> pendingDevFallbackRoutes = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProxyIdentifier proxyId;  // Changed to use ProxyIdentifier
    private volatile PlayerRoutingFeature cachedRoutingFeature;
    private volatile RankService cachedRankService;

    public ProxyConnectionHandler(ProxyServer proxy,
                                  String proxyIdString,
                                  Logger logger,
                                  VelocityServerLifecycleFeature lifecycleFeature,
                                  ServiceLocator serviceLocator) {
        this.proxy = proxy;
        this.proxyId = ProxyIdentifier.fromString(proxyIdString);
        this.logger = logger;
        this.lifecycleFeature = lifecycleFeature;
        this.serviceLocator = serviceLocator;
    }

    /**
     * Update the proxy ID when permanent ID is received from registry
     *
     * @param newProxyIdString The new permanent proxy ID string
     */
    public void updateProxyId(String newProxyIdString) {
        ProxyIdentifier oldId = this.proxyId;

        // Parse the new proxy ID
        ProxyIdentifier newProxyId = ProxyIdentifier.fromString(newProxyIdString);

        this.proxyId = newProxyId;
        logger.info("ProxyConnectionHandler updated proxyId from {} to {} (is permanent: {})",
                oldId.getFormattedId(), newProxyId.getFormattedId(), newProxyId.isPermanent());

        // Warn if updating to a temp ID from a permanent one
        if (newProxyId.isTemporary() && !oldId.isTemporary()) {
            logger.warn("WARNING: ProxyConnectionHandler reverting from permanent ID to temp ID!");
        }
    }

    /**
     * Update the proxy ID when permanent ID is received from registry (with ProxyIdentifier)
     *
     * @param newProxyIdString The new permanent proxy ID string
     * @param identifier       The ProxyIdentifier instance
     */
    public void updateProxyId(String newProxyIdString, ProxyIdentifier identifier) {
        ProxyIdentifier oldId = this.proxyId;
        this.proxyId = identifier;
        logger.info("[ProxyConnectionHandler] Updated proxy ID from {} to {} (using provided ProxyIdentifier)",
                oldId != null ? oldId.getFormattedId() : "null", identifier.getFormattedId());
    }

    /**
     * Get the current proxy ID
     *
     * @return The current proxy ID as a string
     */
    public String getProxyId() {
        return proxyId.getFormattedId();
    }

    /**
     * Get the current proxy identifier
     *
     * @return The current ProxyIdentifier
     */
    public ProxyIdentifier getProxyIdentifier() {
        return proxyId;
    }

    @Subscribe
    public EventTask onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        return EventTask.async(() -> {
            InitialRouteResult result = routeViaRegistry(event);
            if (result == InitialRouteResult.SUCCESS) {
                return;
            }
            if (result == InitialRouteResult.NOT_SUPPORTED) {
                logger.warn("PlayerRoutingFeature unavailable; using legacy lobby selection for {}", event.getPlayer().getUsername());
                legacyInitialRoute(event);
                return;
            }
            if (tryStaffDevFallback(event)) {
                return;
            }
            disconnectNoServers(event);
        });
    }

    /**
     * Find the optimal server based on role and load
     *
     * @param role The server role (e.g., "lobby", "survival", "minigames")
     * @return The optimal server of that role, or null if none available
     */
    public RegisteredServer findOptimalServer(String role) {
        if (role == null) {
            logger.warn("findOptimalServer called with null role - use findAnyOptimalServer() for initial connections");
            return null;
        }

        // Get servers by specific role only
        Set<ServerIdentifier> candidates = lifecycleFeature.getServersByRole(role);

        if (candidates.isEmpty()) {
            logger.debug("No servers found for role: {}", role);
            return null;
        }

        // Sort servers by load factor (lower is better)
        List<ServerWithMetrics> serversWithMetrics = candidates.stream()
                .map(serverIdentifier -> {
                    String serverId = serverIdentifier.getServerId();
                    Optional<RegisteredServer> registered = proxy.getServer(serverId);

                    if (!registered.isPresent()) {
                        return null;
                    }

                    ServerMetrics metrics = serverMetricsCache.get(serverId);

                    // If no metrics or stale, create default metrics
                    if (metrics == null || metrics.isStale()) {
                        metrics = new ServerMetrics(serverId, serverIdentifier.getRole());
                    }

                    return new ServerWithMetrics(registered.get(), metrics);
                })
                .filter(Objects::nonNull)
                .filter(swm -> swm.metrics.isHealthy()) // Only consider healthy servers
                .sorted(Comparator.comparingDouble(swm -> swm.metrics.getLoadFactor()))
                .collect(Collectors.toList());

        if (!serversWithMetrics.isEmpty()) {
            // Select best server (lowest load factor)
            RegisteredServer optimal = serversWithMetrics.get(0).server;
            logger.debug("Selected optimal {} server: {} with load factor: {}",
                    role,
                    optimal.getServerInfo().getName(),
                    serversWithMetrics.get(0).metrics.getLoadFactor());
            return optimal;
        }

        // If no healthy servers of this role, still only check servers of the SAME ROLE
        // This prevents sending players to wrong game modes
        for (ServerIdentifier server : candidates) {
            Optional<RegisteredServer> registered = proxy.getServer(server.getServerId());
            if (registered.isPresent()) {
                logger.debug("No healthy {} servers found, using unhealthy fallback: {}",
                        role, server.getServerId());
                return registered.get();
            }
        }

        logger.debug("No {} servers available at all", role);
        return null;
    }

    /**
     * Find any optimal server for initial player connection
     * This method should ONLY be used for initial connections when no specific role is required
     *
     * @return The optimal server from any role, prioritizing lobby servers
     */
    public RegisteredServer findAnyOptimalServer() {
        // First, try to find a lobby server
        RegisteredServer lobbyServer = findOptimalServer("lobby");
        if (lobbyServer != null) {
            return lobbyServer;
        }

        // If no lobby servers, find the best server from ANY role
        Set<ServerIdentifier> allServers = lifecycleFeature.getRegisteredServers();

        if (allServers.isEmpty()) {
            logger.debug("No servers available at all");
            return null;
        }

        // Sort ALL servers by load factor
        List<ServerWithMetrics> serversWithMetrics = allServers.stream()
                .map(serverIdentifier -> {
                    String serverId = serverIdentifier.getServerId();
                    Optional<RegisteredServer> registered = proxy.getServer(serverId);

                    if (!registered.isPresent()) {
                        return null;
                    }

                    ServerMetrics metrics = serverMetricsCache.get(serverId);

                    if (metrics == null || metrics.isStale()) {
                        metrics = new ServerMetrics(serverId, serverIdentifier.getRole());
                    }

                    return new ServerWithMetrics(registered.get(), metrics);
                })
                .filter(Objects::nonNull)
                .filter(swm -> swm.metrics.isHealthy())
                .sorted(Comparator.comparingDouble(swm -> swm.metrics.getLoadFactor()))
                .collect(Collectors.toList());

        if (!serversWithMetrics.isEmpty()) {
            RegisteredServer optimal = serversWithMetrics.get(0).server;
            logger.info("No lobby servers available - selected {} server as fallback for initial connection",
                    serversWithMetrics.get(0).metrics.role);
            return optimal;
        }

        // Last resort: any server at all
        for (ServerIdentifier server : allServers) {
            Optional<RegisteredServer> registered = proxy.getServer(server.getServerId());
            if (registered.isPresent()) {
                logger.warn("No healthy servers found - using any available server for initial connection: {}",
                        server.getServerId());
                return registered.get();
            }
        }

        return null;
    }

    /**
     * Update server metrics from heartbeat or status change
     */
    public void updateServerMetrics(String serverId, String role, int playerCount,
                                    int maxPlayers, double tps) {
        ServerMetrics metrics = serverMetricsCache.computeIfAbsent(serverId,
                k -> new ServerMetrics(serverId, role));

        // Role can change when ENVIRONMENT is updated; keep cache in sync
        metrics.setRole(role);

        metrics.playerCount = playerCount;
        metrics.maxPlayers = maxPlayers;
        metrics.tps = tps;
        metrics.lastUpdate = System.currentTimeMillis();

        logger.debug("Updated metrics for server {}: players={}/{}, tps={}",
                serverId, playerCount, maxPlayers, tps);
    }

    /**
     * Remove server metrics when server goes offline
     */
    public void removeServerMetrics(String serverId) {
        serverMetricsCache.remove(serverId);
        logger.debug("Removed metrics for server: {}", serverId);
    }

    /**
     * Handle player disconnect event
     */
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        logger.debug("Player {} disconnected from proxy", player.getUsername());
        pendingDevFallbackRoutes.remove(player.getUniqueId());
    }

    /**
     * Handle server pre-connect event
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        logger.debug("Player {} pre-connecting to server {}",
                player.getUsername(),
                event.getOriginalServer().getServerInfo().getName());
    }

    /**
     * Handle server connected event
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        logger.debug("Player {} connected to server {}",
                player.getUsername(),
                server.getServerInfo().getName());
        deliverPendingFallbackRoute(player, server);
    }

    private InitialRouteResult routeViaRegistry(PlayerChooseInitialServerEvent event) {
        Optional<PlayerRoutingFeature> routingFeature = routingFeature();
        if (routingFeature.isEmpty()) {
            return InitialRouteResult.NOT_SUPPORTED;
        }

        Player player = event.getPlayer();
        try {
            PlayerRouteCommand command = routingFeature.get()
                    .requestInitialRoute(player, "lobby", Map.of())
                    .get(LOBBY_ROUTE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            Optional<RegisteredServer> target = proxy.getServer(command.getServerId());
            if (target.isEmpty()) {
                logger.error("Registry routed {} to unknown server {}", player.getUsername(), command.getServerId());
                return InitialRouteResult.FAILED;
            }

            event.setInitialServer(target.get());
            logger.info("Player {} routed to lobby server {} via registry", player.getUsername(), command.getServerId());
            return InitialRouteResult.SUCCESS;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                logger.error("Lobby route request interrupted for {}", player.getUsername(), interrupted);
            } else {
                logger.error("Failed to obtain lobby route for {}: {}", player.getUsername(), ex.getMessage());
            }
            return InitialRouteResult.FAILED;
        }
    }

    private void legacyInitialRoute(PlayerChooseInitialServerEvent event) {
        String playerName = event.getPlayer().getUsername();
        logger.debug("Player {} choosing initial server (legacy fallback)", playerName);

        RegisteredServer targetServer = findAnyOptimalServer();
        if (targetServer == null) {
            if (tryStaffDevFallback(event)) {
                return;
            }
            disconnectNoServers(event);
            return;
        }

        event.setInitialServer(targetServer);
        ServerMetrics metrics = serverMetricsCache.get(targetServer.getServerInfo().getName());
        String serverRole = metrics != null ? metrics.role : "unknown";

        if ("lobby".equalsIgnoreCase(serverRole)) {
            logger.info("Player {} connecting to lobby server: {}", playerName, targetServer.getServerInfo().getName());
        } else {
            logger.warn("Player {} connecting to non-lobby server '{}' (role: {}) - no lobby servers available",
                    playerName, targetServer.getServerInfo().getName(), serverRole);
        }
    }

    private void disconnectNoServers(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getUsername();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String playerUuid = player.getUniqueId().toString();
        String currentProxyId = this.proxyId.getFormattedId();

        logger.error("No lobby servers available for player {} ({}) [Timestamp: {}, Proxy: {} (is permanent: {})]",
                playerName, playerUuid, timestamp, currentProxyId, this.proxyId.isPermanent());

        Component mainMessage = Component.text()
                .append(Component.text("A connection could not be made at this moment.", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("Try again momentarily!", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("If this persists, contact a staff member", NamedTextColor.YELLOW))
                .build();

        Component traceInfo = Component.text()
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Connection Trace:", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("Timestamp: ", NamedTextColor.GRAY))
                .append(Component.text(timestamp, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Proxy ID: ", NamedTextColor.GRAY))
                .append(Component.text(currentProxyId, NamedTextColor.WHITE))
                .build();

        Component fullMessage = Component.text()
                .append(mainMessage)
                .append(traceInfo)
                .build();

        player.disconnect(fullMessage);
        event.setInitialServer(null);
    }

    private Optional<PlayerRoutingFeature> routingFeature() {
        if (cachedRoutingFeature != null) {
            return Optional.of(cachedRoutingFeature);
        }
        if (serviceLocator == null) {
            return Optional.empty();
        }
        cachedRoutingFeature = serviceLocator.getService(PlayerRoutingFeature.class).orElse(null);
        return Optional.ofNullable(cachedRoutingFeature);
    }

    private Optional<RankService> rankService() {
        if (cachedRankService != null) {
            return Optional.of(cachedRankService);
        }
        if (serviceLocator == null) {
            return Optional.empty();
        }
        cachedRankService = serviceLocator.getService(RankService.class).orElse(null);
        return Optional.ofNullable(cachedRankService);
    }

    private boolean tryStaffDevFallback(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return false;
        }
        if (hasActiveServersForRole("lobby")) {
            return false;
        }

        Optional<RankService> rankService = rankService();
        if (rankService.isEmpty()) {
            logger.debug("Cannot evaluate staff fallback for {} - RankService unavailable", player.getUsername());
            return false;
        }

        Rank effectiveRank = rankService.get().getEffectiveRankSync(player.getUniqueId());
        if (effectiveRank == null || !effectiveRank.isStaff()) {
            return false;
        }

        RegisteredServer devServer = findOptimalServer("dev");
        if (devServer == null) {
            logger.info("Staff {} requested development fallback, but no dev servers are online", player.getUsername());
            return false;
        }

        event.setInitialServer(devServer);
        queueDevFallbackRoute(player, devServer);
        Component fallbackNotice = Component.text()
                .append(Component.text("No lobby servers are online. Routing you to ", NamedTextColor.YELLOW))
                .append(Component.text(devServer.getServerInfo().getName(), NamedTextColor.GREEN))
                .append(Component.text(" (dev).", NamedTextColor.YELLOW))
                .build();
        player.sendMessage(fallbackNotice);
        logger.info("Staff {} routed to dev server {} because no lobby servers are online",
                player.getUsername(), devServer.getServerInfo().getName());
        return true;
    }

    private void queueDevFallbackRoute(Player player, RegisteredServer devServer) {
        if (player == null || devServer == null) {
            return;
        }
        PlayerRouteCommand command = new PlayerRouteCommand();
        command.setAction(PlayerRouteCommand.Action.ROUTE);
        command.setRequestId(UUID.randomUUID());
        command.setPlayerId(player.getUniqueId());
        command.setPlayerName(player.getUsername());
        command.setProxyId(proxyId.getFormattedId());
        String serverId = devServer.getServerInfo().getName();
        command.setServerId(serverId);
        command.setSlotId("dev:" + serverId);
        command.setSlotSuffix("dev");
        command.setTargetWorld("");
        command.setSpawnX(0.5D);
        command.setSpawnY(64D);
        command.setSpawnZ(0.5D);
        command.setSpawnYaw(0F);
        command.setSpawnPitch(0F);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("family", "dev");
        metadata.put("variant", "staff-dev");
        metadata.put("routeType", "staff-dev-fallback");
        metadata.put("reason", "staff-dev-fallback");
        metadata.put("skipTeleport", "true");
        metadata.put("targetServer", serverId);
        command.setMetadata(metadata);

        pendingDevFallbackRoutes.put(player.getUniqueId(), command);
    }

    private void deliverPendingFallbackRoute(Player player, RegisteredServer server) {
        if (player == null || server == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerRouteCommand command = pendingDevFallbackRoutes.get(playerId);
        if (command == null) {
            return;
        }
        String connectedServer = server.getServerInfo().getName();
        if (!connectedServer.equalsIgnoreCase(command.getServerId())) {
            logger.debug("Deferring dev fallback route for {} - connected to {} instead of {}",
                    player.getUsername(), connectedServer, command.getServerId());
            return;
        }

        pendingDevFallbackRoutes.remove(playerId);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(command);
            server.sendPluginMessage(ROUTE_CHANNEL, payload);
            logger.info("Registered dev fallback route for {} via {}", player.getUsername(), connectedServer);
        } catch (Exception exception) {
            logger.warn("Failed to deliver dev fallback route for {}: {}", player.getUsername(), exception.getMessage());
        }
    }

    private boolean hasActiveServersForRole(String role) {
        return lifecycleFeature.getServersByRole(role).stream()
                .map(ServerIdentifier::getServerId)
                .anyMatch(lifecycleFeature::isServerActive);
    }

    private enum InitialRouteResult {
        SUCCESS,
        FAILED,
        NOT_SUPPORTED
    }

    /**
     * Internal class to track server metrics for optimal selection
     */
    private static class ServerMetrics {
        private final String serverId;
        private String role;
        private int playerCount = 0;
        private int maxPlayers = 100;
        private double tps = 20.0;
        private long lastUpdate = System.currentTimeMillis();

        public ServerMetrics(String serverId, String role) {
            this.serverId = serverId;
            this.role = role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public double getLoadFactor() {
            // Calculate load factor for optimal selection
            double playerLoad = maxPlayers > 0 ? (double) playerCount / maxPlayers : 1.0;
            double tpsLoad = tps > 0 ? (20.0 - tps) / 20.0 : 1.0;

            // Weighted average: player count 60%, TPS 40%
            return (playerLoad * 0.6) + (tpsLoad * 0.4);
        }

        public boolean isHealthy() {
            // Server is healthy if TPS is above 18 and not at capacity
            return tps >= 18.0 && playerCount < maxPlayers;
        }

        public boolean isStale() {
            // Metrics are stale if not updated in last 10 seconds (5x heartbeat interval of 2s)
            return System.currentTimeMillis() - lastUpdate > 10000;
        }
    }

    /**
         * Helper class to pair server with its metrics
         */
        private record ServerWithMetrics(RegisteredServer server, ServerMetrics metrics) {
    }
}
