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
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ProxyConnectionHandler {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ProxyServer proxy;
    private final String proxyId;
    private final Logger logger;
    private final VelocityServerLifecycleFeature lifecycleFeature;
    
    // Cache server metrics for optimal selection
    private final Map<String, ServerMetrics> serverMetricsCache = new ConcurrentHashMap<>();
    
    public ProxyConnectionHandler(ProxyServer proxy, String proxyId, Logger logger, VelocityServerLifecycleFeature lifecycleFeature) {
        this.proxy = proxy;
        this.proxyId = proxyId;
        this.logger = logger;
        this.lifecycleFeature = lifecycleFeature;
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
            
            // Use optimal server selection for lobby servers
            RegisteredServer targetServer = findOptimalServer("lobby");
            String selectionReason = "";
            
            if (targetServer != null) {
                selectionReason = "Optimal lobby server selected";
                logger.info("Selected optimal lobby server '{}' for player {}",
                    targetServer.getServerInfo().getName(), playerName);
            } else {
                // If no lobby servers, try any available server with optimal selection
                logger.warn("No lobby servers available for player {}, checking other servers...", playerName);
                targetServer = findOptimalServer(null);
                
                if (targetServer != null) {
                    selectionReason = "Optimal server selected (non-lobby)";
                    logger.info("Selected optimal server '{}' for player {}",
                        targetServer.getServerInfo().getName(), playerName);
                }
            }
            
            // Set the selected server or disconnect if none available
            if (targetServer != null) {
                event.setInitialServer(targetServer);
                logger.info("Player {} connecting to initial server: {} ({})",
                    playerName,
                    targetServer.getServerInfo().getName(),
                    selectionReason);
            } else {
                // No servers available at all
                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                String playerUuid = event.getPlayer().getUniqueId().toString();
                
                logger.error("No servers available for player {} ({}) [Timestamp: {}, Proxy: {}]",
                    playerName, playerUuid, timestamp, proxyId);
                
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
     * @param role The server role (e.g., "lobby", "survival", "minigames"), or null for any
     * @return The optimal server, if available
     */
    public RegisteredServer findOptimalServer(String role) {
        Set<ServerIdentifier> candidates;
        
        if (role != null) {
            // Get servers by role
            candidates = lifecycleFeature.getServersByRole(role);
        } else {
            // Get all servers
            candidates = lifecycleFeature.getRegisteredServers();
        }
        
        if (candidates.isEmpty()) {
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
            logger.debug("Selected optimal server: {} with load factor: {}", 
                        optimal.getServerInfo().getName(),
                        serversWithMetrics.get(0).metrics.getLoadFactor());
            return optimal;
        }
        
        // If no healthy servers, fall back to any available server
        for (ServerIdentifier server : candidates) {
            Optional<RegisteredServer> registered = proxy.getServer(server.getServerId());
            if (registered.isPresent()) {
                logger.debug("No healthy servers found, using fallback: {}", server.getServerId());
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