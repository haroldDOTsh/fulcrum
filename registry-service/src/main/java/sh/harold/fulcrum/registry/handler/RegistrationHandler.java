package sh.harold.fulcrum.registry.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles all registration and communication logic for the registry service.
 * This is a simplified version that handles basic Redis pub/sub communication.
 */
public class RegistrationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationHandler.class);
    
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;
    private final ObjectMapper objectMapper;
    private boolean debugMode;
    
    private RedisCommands<String, String> sync;
    private RedisPubSubCommands<String, String> pubSubSync;
    private final ScheduledExecutorService retryExecutor;
    private final Map<String, PendingRequest> pendingRequests;
    
    private static class PendingRequest {
        final String tempId;
        final String json;
        final long timestamp;
        int retryCount;
        
        PendingRequest(String tempId, String json) {
            this.tempId = tempId;
            this.json = json;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }
    
    public RegistrationHandler(ServerRegistry serverRegistry, ProxyRegistry proxyRegistry,
                              HeartbeatMonitor heartbeatMonitor) {
        this(serverRegistry, proxyRegistry, heartbeatMonitor, false);
    }
    
    public RegistrationHandler(ServerRegistry serverRegistry, ProxyRegistry proxyRegistry,
                              HeartbeatMonitor heartbeatMonitor, boolean debugMode) {
        this.serverRegistry = serverRegistry;
        this.proxyRegistry = proxyRegistry;
        this.heartbeatMonitor = heartbeatMonitor;
        this.debugMode = debugMode;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.retryExecutor = Executors.newScheduledThreadPool(2);
        this.pendingRequests = new ConcurrentHashMap<>();
        
        // Set up heartbeat timeout callback
        heartbeatMonitor.setOnServerTimeout(this::handleServerTimeout);
        
        // Start retry monitor
        startRetryMonitor();
    }
    
    /**
     * Set debug mode
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }
    
    /**
     * Initialize Redis connections and subscriptions
     */
    public void initialize(StatefulRedisConnection<String, String> connection,
                          StatefulRedisPubSubConnection<String, String> pubSubConnection) {
        this.sync = connection.sync();
        this.pubSubSync = pubSubConnection.sync();
        
        // Set up message listener
        pubSubConnection.addListener(new RegistryMessageListener());
        
        // Subscribe to channels
        subscribeToChannels();
    }
    
    private void subscribeToChannels() {
        // Server registration and heartbeat channels
        pubSubSync.subscribe(
            "proxy:register",          // Server registration requests (existing channel)
            "server.heartbeat",        // Server heartbeats (existing channel)
            "proxy:discovery",         // Proxy discovery requests
            "proxy:request-registrations" // Request for all servers to register
        );
        
        LOGGER.info("Subscribed to registry channels");
    }
    
    private void startRetryMonitor() {
        retryExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            pendingRequests.entrySet().removeIf(entry -> {
                PendingRequest pending = entry.getValue();
                
                // Remove after 30 seconds
                if (now - pending.timestamp > 30000) {
                    LOGGER.error("[TIMEOUT] Registration failed for {} after 30 seconds", pending.tempId);
                    return true;
                }
                
                // Retry every 10 seconds
                if (now - pending.timestamp > 10000 * (pending.retryCount + 1)) {
                    pending.retryCount++;
                    LOGGER.warn("[RETRY] Resending registration response for {} (attempt {})",
                        pending.tempId, pending.retryCount);
                    try {
                        // Resend the response
                        RegistrationRequest request = objectMapper.readValue(pending.json, RegistrationRequest.class);
                        sendRegistrationResponse(request);
                    } catch (Exception e) {
                        LOGGER.error("Failed to retry registration for {}", pending.tempId, e);
                    }
                }
                return false;
            });
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Handle server registration
     */
    private void handleServerRegistration(String json) {
        if (debugMode) {
            LOGGER.debug("[RECEIVED] Registration request on 'proxy:register' channel");
        }
        try {
            RegistrationRequest request = objectMapper.readValue(json, RegistrationRequest.class);
            
            String tempId = request.getTempId();
            if (debugMode) {
                LOGGER.debug("[PROCESSING] Registration for {} ({}) - Type: {}",
                    request.getTempId(), request.getServerType(), request.getRole());
            }
            
            // Store as pending
            pendingRequests.put(tempId, new PendingRequest(tempId, json));
            
            // Process based on type
            if ("proxy".equals(request.getRole()) || "proxy".equals(request.getServerType())) {
                handleProxyRegistration(request);
            } else {
                // Register the server
                String permanentId = serverRegistry.registerServer(request);
                
                // Send response
                sendRegistrationResponse(request, permanentId);
                
                // Broadcast to all proxies
                broadcastServerAddition(request, permanentId);
            }
            LOGGER.info("Registered {} -> {} (type: {})",
                request.getTempId(),
                request.getRole() != null ? request.getRole() : "server",
                request.getServerType());
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle server registration", e);
        }
    }
    
    /**
     * Handle server heartbeat
     */
    private void handleServerHeartbeat(String json) {
        try {
            Map<String, Object> heartbeat = objectMapper.readValue(json, Map.class);
            String serverId = (String) heartbeat.get("serverId");
            Integer playerCount = (Integer) heartbeat.getOrDefault("playerCount", 0);
            Double tps = (Double) heartbeat.getOrDefault("tps", 20.0);
            
            // Process heartbeat
            heartbeatMonitor.processHeartbeat(serverId, playerCount, tps);
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle server heartbeat", e);
        }
    }
    
    private void handleProxyRegistration(RegistrationRequest request) {
        try {
            String tempId = request.getTempId();
            String address = request.getAddress();
            int port = request.getPort();
            
            // Register the proxy
            String permanentId = proxyRegistry.registerProxy(tempId, address, port);
            
            LOGGER.info("Registered proxy {} -> {} at {}:{}", tempId, permanentId, address, port);
            
            // Create response payload
            Map<String, Object> responsePayload = new HashMap<>();
            responsePayload.put("proxyId", permanentId);
            responsePayload.put("tempId", tempId);
            responsePayload.put("success", true);
            responsePayload.put("message", "Proxy registered successfully");
            responsePayload.put("timestamp", System.currentTimeMillis());
            
            // Wrap in MessageEnvelope for proper MessageBus handling
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "proxy:registration:response");
            envelope.put("senderId", "registry-service");
            envelope.put("targetId", tempId);
            envelope.put("correlationId", java.util.UUID.randomUUID().toString());
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("version", 1);
            envelope.put("payload", responsePayload);
            
            String envelopeJson = objectMapper.writeValueAsString(envelope);
            
            // Send wrapped response through proper channels
            sync.publish("proxy:" + tempId, envelopeJson);
            sync.publish("proxy:register:response", envelopeJson);
            
            if (debugMode) {
                LOGGER.debug("[SENT] Proxy registration response to channels 'proxy:{}' and 'proxy:register:response'", tempId);
            }
            
            // Remove from pending
            pendingRequests.remove(tempId);
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle proxy registration", e);
        }
    }
    
    private void sendRegistrationResponse(RegistrationRequest request) {
        sendRegistrationResponse(request, null);
    }
    
    private void sendRegistrationResponse(RegistrationRequest request, String permanentId) {
        try {
            if (permanentId == null) {
                permanentId = serverRegistry.registerServer(request);
            }
            
            // Create response
            Map<String, Object> response = new HashMap<>();
            response.put("tempId", request.getTempId());
            response.put("assignedServerId", permanentId);
            response.put("success", true);
            response.put("proxyId", "registry");
            response.put("serverType", request.getServerType());
            response.put("address", request.getAddress());
            response.put("port", request.getPort());
            
            String responseJson = objectMapper.writeValueAsString(response);
            
            // Send response to server on both channels for compatibility
            sync.publish("server:registration:response", responseJson);
            sync.publish("server:" + request.getTempId(), responseJson);
            
            if (debugMode) {
                LOGGER.debug("[SENT] Registration response for {} -> {} on channels 'server:registration:response' and 'server:{}'",
                    request.getTempId(), permanentId, request.getTempId());
            }
            
            // Remove from pending on success
            pendingRequests.remove(request.getTempId());
            
        } catch (Exception e) {
            LOGGER.error("Failed to send registration response", e);
        }
    }
    
    private void broadcastServerAddition(RegistrationRequest request, String permanentId) {
        try {
            RegisteredServerData serverInfo = serverRegistry.getServer(permanentId);
            if (serverInfo != null) {
                Map<String, Object> announcement = new HashMap<>();
                announcement.put("serverId", permanentId);
                announcement.put("serverType", request.getServerType());
                announcement.put("family", request.getFamily() != null ? request.getFamily() : "default");
                announcement.put("address", request.getAddress());
                announcement.put("port", request.getPort());
                announcement.put("maxCapacity", request.getMaxCapacity());
                
                String announcementJson = objectMapper.writeValueAsString(announcement);
                sync.publish("registry:server:added", announcementJson);
                
                if (debugMode) {
                    LOGGER.debug("[BROADCAST] Server addition for {} on channel 'registry:server:added'", permanentId);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server addition", e);
        }
    }
    
    /**
     * Handle proxy discovery requests
     * NOTE: This should NOT register new proxies - only respond with existing proxy info
     * Registration happens via 'proxy:register' channel only
     */
    private void handleProxyDiscovery(String json) {
        if (debugMode) {
            LOGGER.debug("[RECEIVED] Proxy discovery request on 'proxy:discovery' channel");
        }
        try {
            // Parse the discovery request
            Map<String, Object> request = objectMapper.readValue(json, Map.class);
            String requesterId = (String) request.get("requesterId");
            
            // This channel is for discovering OTHER proxies, not for registration
            // Send back information about all registered proxies
            Map<String, Object> response = new HashMap<>();
            response.put("proxies", proxyRegistry.getAllProxies());
            response.put("success", true);
            response.put("timestamp", System.currentTimeMillis());
            
            String responseJson = objectMapper.writeValueAsString(response);
            
            // Send response to the requester
            if (requesterId != null) {
                sync.publish("proxy:discovery:response:" + requesterId, responseJson);
                if (debugMode) {
                    LOGGER.debug("[SENT] Discovery response to {} with {} registered proxies",
                        requesterId, proxyRegistry.getProxyCount());
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle proxy discovery", e);
        }
    }
    
    /**
     * Handle server timeout
     */
    private void handleServerTimeout(String serverId) {
        try {
            // Broadcast server removal to all proxies
            Map<String, Object> removal = new HashMap<>();
            removal.put("serverId", serverId);
            
            String json = objectMapper.writeValueAsString(removal);
            sync.publish("registry:server:removed", json);
            
            LOGGER.info("Server timed out and removed: {}", serverId);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal", e);
        }
    }
    
    /**
     * Redis pub/sub listener
     */
    private class RegistryMessageListener implements RedisPubSubListener<String, String> {
        @Override
        public void message(String channel, String message) {
            if (debugMode) {
                LOGGER.debug("[RECEIVED] Message on channel '{}' (length: {} chars)", channel, message.length());
            }
            
            switch (channel) {
                case "proxy:register":
                    handleServerRegistration(message);
                    break;
                case "server.heartbeat":
                    handleServerHeartbeat(message);
                    break;
                case "proxy:discovery":
                    handleProxyDiscovery(message);
                    break;
                case "proxy:request-registrations":
                    // Proxies are requesting all servers to re-register
                    // This is handled by the servers themselves
                    if (debugMode) {
                        LOGGER.debug("[RECEIVED] Proxy requested re-registration of all servers");
                    }
                    break;
                default:
                    if (debugMode) {
                        LOGGER.debug("Received message on unhandled channel: {}", channel);
                    }
            }
        }
        
        @Override
        public void message(String pattern, String channel, String message) {
            // Not used
        }
        
        @Override
        public void subscribed(String channel, long count) {
            LOGGER.debug("Subscribed to channel: {} (total: {})", channel, count);
        }
        
        @Override
        public void unsubscribed(String channel, long count) {
            LOGGER.debug("Unsubscribed from channel: {} (total: {})", channel, count);
        }
        
        @Override
        public void psubscribed(String pattern, long count) {
            // Not used
        }
        
        @Override
        public void punsubscribed(String pattern, long count) {
            // Not used
        }
    }
    
    public void shutdown() {
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
        }
    }
}