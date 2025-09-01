package sh.harold.fulcrum.registry.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
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
    
    private final ScheduledExecutorService retryExecutor;
    private final Map<String, PendingRequest> pendingRequests;
    private MessageBus messageBus;
    
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
     * Initialize MessageBus subscriptions
     */
    public void initialize(MessageBus messageBus) {
        this.messageBus = messageBus;
        
        // Subscribe to channels with message handlers
        subscribeToChannels();
    }
    
    /**
     * Set the MessageBus instance
     */
    public void setMessageBus(MessageBus messageBus) {
        this.messageBus = messageBus;
    }
    
    private void subscribeToChannels() {
        // Subscribe to registration requests
        messageBus.subscribe("registry:register", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    String json = objectMapper.writeValueAsString(envelope.getPayload());
                    handleServerRegistration(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle registration", e);
                }
            }
        });
        
        // Subscribe to heartbeat messages
        messageBus.subscribe("server:heartbeat", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    // Convert full envelope to JSON for heartbeat processing
                    Map<String, Object> envelopeMap = new HashMap<>();
                    envelopeMap.put("type", envelope.getType());
                    envelopeMap.put("senderId", envelope.getSenderId());
                    envelopeMap.put("payload", objectMapper.convertValue(envelope.getPayload(), Map.class));
                    String json = objectMapper.writeValueAsString(envelopeMap);
                    handleServerHeartbeat(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle heartbeat", e);
                }
            }
        });
        
        // Subscribe to proxy unregister messages
        messageBus.subscribe("proxy:unregister", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    String json = objectMapper.writeValueAsString(envelope.getPayload());
                    handleProxyUnregister(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle proxy unregister", e);
                }
            }
        });
        
        // Subscribe to evacuation requests
        messageBus.subscribe("server:evacuation", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    String json = objectMapper.writeValueAsString(envelope.getPayload());
                    handleEvacuationRequest(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle evacuation request", e);
                }
            }
        });
        
        LOGGER.info("Subscribed to registry channels via MessageBus");
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
            LOGGER.debug("[RECEIVED] Registration request on registry channel");
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
                LOGGER.debug("[DIAGNOSTIC] Processing proxy registration for tempId: {}", tempId);
                handleProxyRegistration(request);
                // Note: Proxy registration already logs in handleProxyRegistration, skip generic log
                
                // DIAGNOSTIC: Check if the generic log is still being triggered
                LOGGER.debug("[DIAGNOSTIC] After handleProxyRegistration - should NOT log generic message");
                
                // BUG FIX: The generic log was still happening after proxy registration!
                // This log should NOT appear for proxies
                // Commenting it out - it was creating the 4th log message
                // LOGGER.info("Registered {} -> {} (type: {})",
                //     request.getTempId(),
                //     "proxy",
                //     request.getServerType());
            } else {
                // Register the server
                String permanentId = serverRegistry.registerServer(request);
                
                // Initialize heartbeat tracking for the new server
                heartbeatMonitor.processHeartbeat(permanentId, 0, 20.0);
                
                // Send response
                sendRegistrationResponse(request, permanentId);
                
                // Broadcast to all proxies
                broadcastServerAddition(request, permanentId);
                
                // Log only for non-proxy registrations
                LOGGER.info("Registered {} -> {} (type: {})",
                    request.getTempId(),
                    request.getRole() != null ? request.getRole() : "server",
                    request.getServerType());
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle server registration", e);
        }
    }
    
    /**
     * Handle server heartbeat
     */
    private void handleServerHeartbeat(String json) {
        try {
            // Debug: Log raw JSON
            if (debugMode) {
                LOGGER.debug("[HEARTBEAT] Raw JSON: {}", json);
            }
            
            Map<String, Object> heartbeat = null;
            
            // First try to parse as a Map directly
            try {
                heartbeat = objectMapper.readValue(json, Map.class);
                
                // Check if this is actually a MessageEnvelope wrapper
                if (heartbeat.containsKey("payload") && heartbeat.containsKey("type")) {
                    // It's a MessageEnvelope, extract the payload
                    Object payload = heartbeat.get("payload");
                    if (payload instanceof Map) {
                        heartbeat = (Map<String, Object>) payload;
                        if (debugMode) {
                            LOGGER.debug("[HEARTBEAT] Extracted heartbeat from MessageEnvelope wrapper");
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse heartbeat JSON", e);
                return;
            }
            
            String serverId = (String) heartbeat.get("serverId");
            
            // Debug: Log extracted serverId
            if (debugMode && serverId != null) {
                LOGGER.debug("[HEARTBEAT] Extracted serverId: {} (isProxy: {})",
                    serverId, serverId.startsWith("fulcrum-proxy-"));
            }
            
            // Check if this is a proxy shutdown signal
            String status = (String) heartbeat.get("status");
            if ("SHUTDOWN".equals(status) && serverId != null &&
                (serverId.startsWith("proxy-") || serverId.startsWith("fulcrum-proxy-"))) {
                handleProxyShutdown(serverId);
                return;
            }
            
            // Extract metrics from heartbeat
            Number playerCountNum = (Number) heartbeat.getOrDefault("playerCount", 0);
            int playerCount = playerCountNum.intValue();
            
            Number tpsNum = (Number) heartbeat.getOrDefault("tps", 20.0);
            double tps = tpsNum.doubleValue();
            
            // Process heartbeat through monitor (handles both servers and proxies)
            heartbeatMonitor.processHeartbeat(serverId, playerCount, tps);
            
            if (debugMode) {
                LOGGER.debug("[HEARTBEAT] Received from {} - Players: {}, TPS: {:.1f}",
                    serverId, playerCount, tps);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle server heartbeat", e);
        }
    }
    
    private void handleProxyRegistration(RegistrationRequest request) {
        try {
            String tempId = request.getTempId();
            String address = request.getAddress();
            int port = request.getPort();
            
            // DIAGNOSTIC: Log entry point
            LOGGER.info("[DIAGNOSTIC] handleProxyRegistration START - tempId: {}", tempId);
            
            // Register the proxy
            String permanentId = proxyRegistry.registerProxy(tempId, address, port);
            
            // DIAGNOSTIC: Check if there was an old log statement here
            LOGGER.info("[DIAGNOSTIC] After registerProxy - permanentId: {}", permanentId);
            
            // NOTE: This log was causing duplicate logging - REMOVED
            // LOGGER.info("Registered proxy {} -> {} at {}:{}", tempId, permanentId, address, port);
            
            // ProxyRegistry already logs the registration details
            
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
            
            // Send to proxy:registration:response channel via MessageBus
            messageBus.send(tempId, "proxy:registration:response", responsePayload);
            if (debugMode) {
                LOGGER.debug("[SENT] Proxy registration response via MessageBus to 'proxy:registration:response'");
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
            
            // Send to server:registration:response channel via MessageBus
            messageBus.send(request.getTempId(), "server:registration:response", response);
            // Also send to server-specific channel for compatibility
            String responseChannel = "server:" + request.getTempId() + ":registration:response";
            messageBus.send(request.getTempId(), responseChannel, response);
            
            if (debugMode) {
                LOGGER.debug("[SENT] Server registration response via MessageBus to 'server:registration:response' and '{}'",
                    responseChannel);
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
                announcement.put("role", request.getRole() != null ? request.getRole() : "default");
                announcement.put("address", request.getAddress());
                announcement.put("port", request.getPort());
                announcement.put("maxCapacity", request.getMaxCapacity());
                
                // Broadcast server addition via MessageBus
                messageBus.broadcast("registry:server:added", announcement);
                if (debugMode) {
                    LOGGER.debug("[BROADCAST] Server addition via MessageBus on channel 'registry:server:added'");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server addition", e);
        }
    }
    
    
    /**
     * Handle proxy unregister notification
     */
    private void handleProxyUnregister(String json) {
        try {
            Map<String, Object> request = objectMapper.readValue(json, Map.class);
            String proxyId = (String) request.get("proxyId");
            
            if (proxyId != null) {
                handleProxyShutdown(proxyId);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle proxy unregister", e);
        }
    }
    
    /**
     * Handle server evacuation request
     */
    private void handleEvacuationRequest(String json) {
        try {
            Map<String, Object> request = objectMapper.readValue(json, Map.class);
            String serverId = (String) request.get("serverId");
            String reason = (String) request.get("reason");
            
            if (serverId != null) {
                LOGGER.info("Evacuation requested for server {} - Reason: {}", serverId, reason);
                
                // Update server status to EVACUATING
                serverRegistry.updateStatus(serverId, "EVACUATING");
                
                // Broadcast status change
                Map<String, Object> statusChange = new HashMap<>();
                statusChange.put("serverId", serverId);
                statusChange.put("status", "EVACUATING");
                statusChange.put("reason", reason);
                statusChange.put("timestamp", System.currentTimeMillis());
                
                // Broadcast status change via MessageBus
                messageBus.broadcast("registry:status:change", statusChange);
                
                // Send evacuation response
                Map<String, Object> response = new HashMap<>();
                response.put("serverId", serverId);
                response.put("success", true);
                response.put("timestamp", System.currentTimeMillis());
                
                messageBus.broadcast("server:evacuation:response", response);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle evacuation request", e);
        }
    }
    
    /**
     * Handle proxy shutdown
     */
    private void handleProxyShutdown(String proxyId) {
        LOGGER.info("Proxy {} is shutting down - removing from registry", proxyId);
        
        // Remove the proxy from registry (ephemeral behavior)
        proxyRegistry.deregisterProxy(proxyId);
        
        // Broadcast proxy removal
        try {
            Map<String, Object> removal = new HashMap<>();
            removal.put("proxyId", proxyId);
            removal.put("reason", "shutdown");
            removal.put("timestamp", System.currentTimeMillis());
            
            // Broadcast proxy removal via MessageBus
            messageBus.broadcast("registry:proxy:removed", removal);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast proxy removal for {}", proxyId, e);
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
            removal.put("reason", "timeout");
            removal.put("timestamp", System.currentTimeMillis());
            
            // Broadcast server removal via MessageBus
            messageBus.broadcast("registry:server:removed", removal);
            
            LOGGER.warn("Server {} timed out and was removed from registry", serverId);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal for {}", serverId, e);
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