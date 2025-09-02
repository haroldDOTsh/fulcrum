package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.api.ServerIdentifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ProxyConnectionHandler {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ProxyServer proxy;
    private String proxyId;  // Changed from final to allow updates
    private final Logger logger;
    private final VelocityServerLifecycleFeature lifecycleFeature;
    
    // Cache server metrics for optimal selection
    private final Map<String, ServerMetrics> serverMetricsCache = new ConcurrentHashMap<>();
    
    public ProxyConnectionHandler(ProxyServer proxy, String proxyId, Logger logger, VelocityServerLifecycleFeature lifecycleFeature) {
        this.proxy = proxy;
        this.proxyId = proxyId;
        this.logger = logger;
        this.lifecycleFeature = lifecycleFeature;
        logger.info("[DIAGNOSTIC] ProxyConnectionHandler initialized with proxyId: {}", proxyId);
    }
    
    /**
     * Update the proxy ID when permanent ID is received from registry
     * @param newProxyId The new permanent proxy ID
     */
    public void updateProxyId(String newProxyId) {
        String oldId = this.proxyId;
        this.proxyId = newProxyId;
        logger.info("[DIAGNOSTIC] ProxyConnectionHandler updated proxyId from {} to {}", oldId, newProxyId);
    }
    
    /**
     * Internal class to track server metrics for optimal selection
     */
    private static class ServerMetrics {
        private final String serverId;
        private final String role;
        private int playerCount = 0;
        private int maxPlayers = 100;
        private double tps = 20.0;
        private long lastUpdate = System.currentTimeMillis();
        
        public ServerMetrics(String serverId, String role) {
            this.serverId = serverId;
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
    
    @Subscribe
    public EventTask onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        return EventTask.async(() -> {
            String playerName = event.getPlayer().getUsername();
            logger.info("=== PlayerChooseInitialServerEvent ===");
            logger.info("Player: {}", playerName);
            
            // For initial connections, use the special method that can fall back to any server
            RegisteredServer targetServer = findAnyOptimalServer();
            
            // Set the selected server or disconnect if none available
            if (targetServer != null) {
                event.setInitialServer(targetServer);
                
                // Check if it's a lobby server or fallback
                ServerMetrics metrics = serverMetricsCache.get(targetServer.getServerInfo().getName());
                String serverRole = metrics != null ? metrics.role : "unknown";
                
                if ("lobby".equalsIgnoreCase(serverRole)) {
                    logger.info("Player {} connecting to lobby server: {}",
                        playerName, targetServer.getServerInfo().getName());
                } else {
                    logger.warn("Player {} connecting to non-lobby server '{}' (role: {}) - no lobby servers available",
                        playerName, targetServer.getServerInfo().getName(), serverRole);
                }
            } else {
                // No servers available at all
                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                String playerUuid = event.getPlayer().getUniqueId().toString();
                
                logger.error("No servers available for player {} ({}) [Timestamp: {}, Proxy: {}]",
                    playerName, playerUuid, timestamp, proxyId);
                logger.info("[DIAGNOSTIC] Current proxyId in disconnect message: {}", proxyId);
                
                // Build the disconnection message
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
                    .append(Component.text(proxyId, NamedTextColor.WHITE))
                    .build();
                
                Component fullMessage = Component.text()
                    .append(mainMessage)
                    .append(traceInfo)
                    .build();
                
                event.getPlayer().disconnect(fullMessage);
                event.setInitialServer(null);
            }
        });
    }
    
    /**
     * Find the optimal server based on role and load
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
     * Helper class to pair server with its metrics
     */
    private static class ServerWithMetrics {
        final RegisteredServer server;
        final ServerMetrics metrics;
        
        ServerWithMetrics(RegisteredServer server, ServerMetrics metrics) {
            this.server = server;
            this.metrics = metrics;
        }
    }
}