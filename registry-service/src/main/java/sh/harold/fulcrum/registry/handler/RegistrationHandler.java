package sh.harold.fulcrum.registry.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    
    // State management for ongoing proxy registrations
    private final Map<String, CompletableFuture<String>> ongoingProxyRegistrations = new ConcurrentHashMap<>();
    private static final long REGISTRATION_TIMEOUT_SECONDS = 30;
    
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
        if (this.messageBus != null) {
            LOGGER.warn("MessageBus was already set! Overwriting previous instance");
        }
        
        this.messageBus = messageBus;
        
        if (this.messageBus == null) {
            throw new IllegalStateException("MessageBus cannot be null");
        }
        
        // Subscribe to channels with message handlers
        subscribeToChannels();
        
        LOGGER.info("RegistrationHandler initialized");
    }
    
    /**
     * Set the MessageBus instance
     */
    public void setMessageBus(MessageBus messageBus) {
        this.messageBus = messageBus;
    }
    
    private void subscribeToChannels() {
        if (messageBus == null) {
            throw new IllegalStateException("MessageBus is null in subscribeToChannels");
        }
        
        // Subscribe to registration requests
        
        // Create the handler first to ensure it's not null
        MessageHandler registrationHandler = new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                LOGGER.debug("Registration handler invoked");
                
                try {
                    Object payload = envelope.getPayload();
                    
                    // Check if payload is already a ServerRegistrationRequest
                    if (payload instanceof sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest) {
                        sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest request =
                            (sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest) payload;
                        
                        // Convert to JSON for processing
                        String json = objectMapper.writeValueAsString(request);
                        handleServerRegistration(json);
                    } else if (payload instanceof Map) {
                        String json = objectMapper.writeValueAsString(payload);
                        handleServerRegistration(json);
                    } else {
                        // Try to convert anyway
                        String json = objectMapper.writeValueAsString(payload);
                        handleServerRegistration(json);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to handle registration message", e);
                }
            }
        };
        
        // Subscribe with the handler
        messageBus.subscribe("registry:register", registrationHandler);
        
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
        
        // Subscribe to server removal notifications
        messageBus.subscribe("registry:server:remove", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    // Try to deserialize as ServerRemovalNotification
                    ServerRemovalNotification notification = objectMapper.convertValue(
                        envelope.getPayload(), ServerRemovalNotification.class);
                    handleServerRemovalNotification(notification);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle server removal notification", e);
                }
            }
        });
        
        // Subscribe to proxy channel for shutdown notifications
        messageBus.subscribe("registry:proxy", new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    Object payload = envelope.getPayload();
                    
                    // Handle JsonNode payloads (which come from the MessageBus deserialization)
                    if (payload instanceof com.fasterxml.jackson.databind.JsonNode) {
                        try {
                            // Convert JsonNode to ServerRemovalNotification
                            ServerRemovalNotification notification = objectMapper.treeToValue(
                                (com.fasterxml.jackson.databind.JsonNode) payload,
                                ServerRemovalNotification.class
                            );
                            if ("PROXY".equalsIgnoreCase(notification.getServerType())) {
                                handleProxyRemoval(notification);
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to convert JsonNode to ServerRemovalNotification", e);
                        }
                    }
                    // Check if this is already a ServerRemovalNotification
                    else if (payload instanceof ServerRemovalNotification) {
                        ServerRemovalNotification notification = (ServerRemovalNotification) payload;
                        if ("PROXY".equalsIgnoreCase(notification.getServerType())) {
                            handleProxyRemoval(notification);
                        }
                    } else if (payload instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) payload;
                        // Check if it has the fields of a ServerRemovalNotification
                        if (map.containsKey("serverId") && map.containsKey("serverType") && map.containsKey("reason")) {
                            ServerRemovalNotification notification = objectMapper.convertValue(
                                payload, ServerRemovalNotification.class);
                            
                            if ("PROXY".equalsIgnoreCase(notification.getServerType())) {
                                RegistrationHandler.this.handleProxyRemoval(notification);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to handle message on registry:proxy channel", e);
                }
            }
        });
    }
    
    private void startRetryMonitor() {
        // Clean up completed registration futures periodically
        retryExecutor.scheduleAtFixedRate(() -> {
            cleanupCompletedRegistrations();
        }, 60, 60, TimeUnit.SECONDS);
        
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
            LOGGER.debug("Registration request received");
        }
        
        try {
            // ALL services now use ServerRegistrationRequest from message-bus-api
            // This includes: backend servers, limbo service, and proxy servers
            sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest apiRequest =
                objectMapper.readValue(json, sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest.class);
            
            // Convert to internal RegistrationRequest format for processing
            RegistrationRequest request = new RegistrationRequest();
            request.setTempId(apiRequest.getTempId());
            request.setServerType(apiRequest.getServerType());
            request.setRole(apiRequest.getRole());
            request.setAddress(apiRequest.getAddress());
            request.setPort(apiRequest.getPort());
            request.setMaxCapacity(apiRequest.getMaxCapacity());
            
            String tempId = request.getTempId();
            
            // Check if already being processed (prevent duplicate processing)
            PendingRequest existing = pendingRequests.putIfAbsent(tempId, new PendingRequest(tempId, json));
            if (existing != null) {
                if (debugMode) {
                    LOGGER.debug("Registration for {} already in progress, skipping duplicate", tempId);
                }
                return;
            }
            
            // Process based on type
            if ("proxy".equals(request.getRole()) || "proxy".equals(request.getServerType())) {
                handleProxyRegistration(request);
            } else {
                // Register the server
                String permanentId = serverRegistry.registerServer(request);
                
                // Initialize heartbeat tracking for the new server
                heartbeatMonitor.processHeartbeat(permanentId, 0, 20.0);
                
                // Send response
                sendRegistrationResponse(request, permanentId);
                
                // Broadcast to all proxies
                broadcastServerAddition(request, permanentId);
                
                // Log only for non-proxy registrations (essential info)
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
            
            // Check if this is a shutdown signal (for ANY server, not just proxies)
            String status = (String) heartbeat.get("status");
            if ("SHUTDOWN".equals(status) && serverId != null) {
                LOGGER.info("[HEARTBEAT] Received SHUTDOWN status from server: {}", serverId);
                // Handle graceful shutdown based on server type
                if (serverId.startsWith("proxy-") || serverId.startsWith("fulcrum-proxy-")) {
                    LOGGER.info("[HEARTBEAT] Processing graceful shutdown for proxy: {}", serverId);
                    // Graceful shutdown from heartbeat - immediately release ID
                    handleProxyGracefulShutdown(serverId);
                } else {
                    LOGGER.info("[HEARTBEAT] Processing shutdown for backend server: {}", serverId);
                    // Handle backend server shutdown
                    handleServerShutdown(serverId);
                }
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
            
            // Check if a registration is already in progress for this tempId
            CompletableFuture<String> existingFuture = ongoingProxyRegistrations.get(tempId);
            if (existingFuture != null && !existingFuture.isDone()) {
                LOGGER.info("Registration already in progress for tempId: {}, waiting for completion", tempId);
                existingFuture.whenComplete((proxyId, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Existing registration failed for tempId: {}", tempId, throwable);
                        sendProxyRegistrationResponse(tempId, null, false, "Registration failed: " + throwable.getMessage());
                    } else {
                        LOGGER.info("Reusing result from ongoing registration - ProxyID: {}", proxyId);
                        sendProxyRegistrationResponse(tempId, proxyId, true, "Proxy registered successfully (reused)");
                    }
                    // Remove from pending
                    pendingRequests.remove(tempId);
                });
                return;
            }
            
            // Check if this proxy was recently registered (within 30 seconds)
            // This helps prevent duplicate registrations during registry startup
            String existingProxyId = proxyRegistry.getProxyByAddress(address, port);
            if (existingProxyId != null && proxyRegistry.wasRecentlyRegistered(existingProxyId, 30000)) {
                LOGGER.info("Proxy at {}:{} was recently registered as {} (within 30s), skipping re-registration",
                           address, port, existingProxyId);
                
                // Send the existing proxy ID back as response
                sendProxyRegistrationResponse(tempId, existingProxyId, true, "Proxy already registered (recent registration)");
                
                // Remove from pending
                pendingRequests.remove(tempId);
                return;
            }
            
            // Log registration attempt details in debug mode
            if (debugMode) {
                LOGGER.debug("Proxy registration attempt:");
                LOGGER.debug("  - Temp ID: {}", tempId);
                LOGGER.debug("  - Address: {}:{}", address, port);
                LOGGER.debug("  - Is temp ID: {}", tempId.startsWith("temp-"));
                LOGGER.debug("  - Existing proxies: {}", proxyRegistry.getAllProxies().size());
            }
            
            // Check if this proxy might already be registered
            if (!tempId.startsWith("temp-") && proxyRegistry.getProxy(tempId) != null) {
                RegisteredProxyData existingProxy = proxyRegistry.getProxy(tempId);
                if (existingProxy != null && proxyRegistry.wasRecentlyRegistered(tempId, 30000)) {
                    LOGGER.info("Proxy {} is already registered and was registered recently (within 30s), skipping",
                               tempId);
                    
                    // Send confirmation with existing ID
                    sendProxyRegistrationResponse(tempId, tempId, true, "Proxy already registered");
                    
                    // Remove from pending
                    pendingRequests.remove(tempId);
                    return;
                }
            }
            
            // Create a new CompletableFuture for this registration
            CompletableFuture<String> registrationFuture = CompletableFuture.supplyAsync(() -> {
                return performProxyRegistration(tempId, address, port);
            });
            
            // Store the future in the ongoing registrations map before setting up completion handler
            ongoingProxyRegistrations.put(tempId, registrationFuture);
            
            // Add timeout handling
            registrationFuture.orTimeout(REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((proxyId, throwable) -> {
                    // Remove from ongoing registrations map
                    ongoingProxyRegistrations.remove(tempId);
                    
                    if (throwable != null) {
                        if (throwable instanceof TimeoutException) {
                            LOGGER.error("Registration timeout for tempId: {} after {} seconds",
                                tempId, REGISTRATION_TIMEOUT_SECONDS);
                            sendProxyRegistrationResponse(tempId, null, false, "Registration timeout");
                        } else {
                            LOGGER.error("Registration failed for tempId: {}", tempId, throwable);
                            sendProxyRegistrationResponse(tempId, null, false, "Registration failed: " + throwable.getMessage());
                        }
                    } else {
                        LOGGER.info("Registration completed successfully - ProxyID: {}", proxyId);
                        sendProxyRegistrationResponse(tempId, proxyId, true, "Proxy registered successfully");
                    }
                    
                    // Remove from pending
                    pendingRequests.remove(tempId);
                });
            
        } catch (Exception e) {
            LOGGER.error("Failed to handle proxy registration", e);
            // Clean up on error
            ongoingProxyRegistrations.remove(request.getTempId());
            pendingRequests.remove(request.getTempId());
        }
    }
    
    /**
     * Perform the actual proxy registration
     */
    private String performProxyRegistration(String tempId, String address, int port) {
        try {
            // Register the proxy - ProxyRegistry will handle deduplication internally
            String permanentId = proxyRegistry.registerProxy(tempId, address, port);
            
            if (debugMode) {
                LOGGER.debug("Proxy registered: {} -> {}", tempId, permanentId);
            }
            
            // Initialize heartbeat tracking for the newly registered proxy
            // The proxy will send heartbeats with the permanent ID after receiving the response
            heartbeatMonitor.processHeartbeat(permanentId, 0, 20.0);
            
            // Also track by temp ID temporarily in case proxy sends heartbeat before updating its ID
            heartbeatMonitor.processHeartbeat(tempId, 0, 20.0);
            
            return permanentId;
        } catch (Exception e) {
            LOGGER.error("Failed to perform proxy registration for tempId: {}", tempId, e);
            throw new RuntimeException("Failed to register proxy: " + e.getMessage(), e);
        }
    }
    
    /**
     * Send proxy registration response
     */
    private void sendProxyRegistrationResponse(String tempId, String proxyId, boolean success, String message) {
        try {
            Map<String, Object> responsePayload = new HashMap<>();
            responsePayload.put("proxyId", proxyId);
            responsePayload.put("tempId", tempId);
            responsePayload.put("success", success);
            responsePayload.put("message", message);
            responsePayload.put("timestamp", System.currentTimeMillis());
            
            // Send to proxy:registration:response channel via MessageBus
            messageBus.broadcast("proxy:registration:response", responsePayload);
            
            if (debugMode) {
                LOGGER.debug("Sent proxy registration response for tempId: {} -> proxyId: {}", tempId, proxyId);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send proxy registration response for tempId: {}", tempId, e);
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
            
            // CRITICAL: Use broadcast instead of send because the backend is listening on a shared channel
            // The backend server subscribes to "server:registration:response" to receive responses
            LOGGER.info("[REGISTRY-DEBUG] Broadcasting server registration response:");
            LOGGER.info("[REGISTRY-DEBUG]   - tempId: {}", request.getTempId());
            LOGGER.info("[REGISTRY-DEBUG]   - assignedServerId: {}", permanentId);
            LOGGER.info("[REGISTRY-DEBUG]   - channel: server:registration:response");
            LOGGER.info("[REGISTRY-DEBUG]   - response object: {}", response);
            
            messageBus.broadcast("server:registration:response", response);
            
            // Also broadcast to server-specific channel for redundancy
            String responseChannel = "server:" + request.getTempId() + ":registration:response";
            LOGGER.info("[REGISTRY-DEBUG] Also broadcasting to specific channel: {}", responseChannel);
            messageBus.broadcast(responseChannel, response);
            
            if (debugMode) {
                LOGGER.debug("[SENT] Server registration response via MessageBus broadcast to 'server:registration:response' and '{}'",
                    responseChannel);
            }
            
            LOGGER.info("Sent registration confirmation to {} -> {} via broadcast",
                request.getTempId(), permanentId);
            
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
                // Essential log - always show evacuation requests
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
     * Handle server removal notification from backend servers
     */
    private void handleServerRemovalNotification(ServerRemovalNotification notification) {
        String serverId = notification.getServerId();
        String reason = notification.getReason();
        
        LOGGER.info("Received server removal notification for {} - Reason: {}", serverId, reason);
        
        // Mark the server as stopping
        serverRegistry.updateStatus(serverId, "STOPPING");
        
        // Remove the server from the registry (deregisterServer handles cleanup)
        serverRegistry.deregisterServer(serverId);
        
        // Broadcast server removal to all proxies and other listeners
        try {
            Map<String, Object> removal = new HashMap<>();
            removal.put("serverId", serverId);
            removal.put("serverType", notification.getServerType());
            removal.put("reason", reason);
            removal.put("timestamp", notification.getTimestamp());
            
            // Broadcast server removal via MessageBus
            messageBus.broadcast("registry:server:removed", removal);
            
            LOGGER.info("Server {} removed from registry (reason: {})", serverId, reason);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal for {}", serverId, e);
        }
    }
    
    /**
     * Handle backend server shutdown from heartbeat
     */
    private void handleServerShutdown(String serverId) {
        LOGGER.info("Server {} is shutting down - removing from registry", serverId);
        
        // Update server status
        serverRegistry.updateStatus(serverId, "STOPPING");
        
        // Remove the server from the registry (deregisterServer handles cleanup)
        serverRegistry.deregisterServer(serverId);
        
        // Broadcast server removal
        try {
            Map<String, Object> removal = new HashMap<>();
            removal.put("serverId", serverId);
            removal.put("reason", "shutdown");
            removal.put("timestamp", System.currentTimeMillis());
            
            // Broadcast server removal via MessageBus
            messageBus.broadcast("registry:server:removed", removal);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal for {}", serverId, e);
        }
    }
    
    /**
     * Handle proxy shutdown from heartbeat timeout (reserve ID for reconnection)
     */
    private void handleProxyShutdown(String proxyId) {
        LOGGER.info("Proxy {} timed out - marking as unavailable (ID reserved for reconnection)", proxyId);
        
        // Remove from heartbeat monitoring
        heartbeatMonitor.unregisterProxy(proxyId);
        
        // Mark proxy as DEAD which will move it to unavailable list (ID reserved)
        proxyRegistry.updateProxyStatus(proxyId, RegisteredProxyData.Status.DEAD);
        
        LOGGER.info("Marked proxy {} as DEAD (timeout - ID reserved for potential reconnection)", proxyId);
        
        // Broadcast proxy unavailability
        try {
            Map<String, Object> removal = new HashMap<>();
            removal.put("proxyId", proxyId);
            removal.put("reason", "timeout");
            removal.put("status", "unavailable");
            removal.put("timestamp", System.currentTimeMillis());
            
            messageBus.broadcast("registry:proxy:unavailable", removal);
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast proxy unavailability for {}", proxyId, e);
        }
    }
    
    /**
     * Handle proxy graceful shutdown from heartbeat (immediately release ID)
     */
    private void handleProxyGracefulShutdown(String proxyId) {
        // Remove from heartbeat monitoring
        heartbeatMonitor.unregisterProxy(proxyId);
        
        // IMMEDIATELY remove proxy and release ID (graceful shutdown)
        boolean removed = proxyRegistry.removeProxyImmediately(proxyId);
        
        if (removed) {
            LOGGER.info("Proxy {} removed - graceful shutdown from heartbeat", proxyId);
            
            // Get proxy details for broadcast (if available)
            RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);
            
            // Broadcast removal to other services
            Map<String, Object> removalInfo = new HashMap<>();
            removalInfo.put("proxyId", proxyId);
            if (proxy != null) {
                removalInfo.put("address", proxy.getAddress());
                removalInfo.put("port", proxy.getPort());
            }
            removalInfo.put("reason", "graceful_shutdown");
            removalInfo.put("gracefulShutdown", true);
            removalInfo.put("timestamp", System.currentTimeMillis());
            
            try {
                messageBus.broadcast("registry:proxy:removed", removalInfo);
            } catch (Exception e) {
                LOGGER.error("Failed to broadcast proxy removal for {}", proxyId, e);
            }
        } else {
            LOGGER.warn("Failed to remove proxy from registry: {}", proxyId);
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
            
            // Essential log - always show server timeouts
            LOGGER.warn("Server {} timed out and was removed from registry (blacklisted for 60 seconds)", serverId);
            
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal for {}", serverId, e);
        }
    }
    
    /**
     * Handle proxy removal notification (ServerRemovalNotification from proxy - graceful shutdown)
     */
    private void handleProxyRemoval(ServerRemovalNotification notification) {
        try {
            String proxyId = notification.getServerId();
            
            // Check if proxy exists in registry
            RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);
            
            if (proxy != null) {
                // Unregister from heartbeat monitoring
                heartbeatMonitor.unregisterProxy(proxyId);
                
                // IMMEDIATELY remove proxy and release ID (graceful shutdown)
                boolean removed = proxyRegistry.removeProxyImmediately(proxyId);
                
                if (removed) {
                    LOGGER.info("Proxy {} removed - graceful shutdown", proxyId);
                    
                    // Broadcast removal to other services
                    Map<String, Object> removalInfo = new HashMap<>();
                    removalInfo.put("proxyId", proxy.getProxyId());
                    removalInfo.put("address", proxy.getAddress());
                    removalInfo.put("port", proxy.getPort());
                    removalInfo.put("reason", notification.getReason());
                    removalInfo.put("gracefulShutdown", true);
                    removalInfo.put("timestamp", System.currentTimeMillis());
                    
                    messageBus.broadcast("registry:proxy:removed", removalInfo);
                } else {
                    LOGGER.warn("Failed to remove proxy from registry: {}", proxy.getProxyId());
                }
            } else {
                // Try with the ID as a temporary ID first
                String permanentId = proxyRegistry.getPermanentId(proxyId);
                if (permanentId != null) {
                    // proxyId was actually a temporary ID, use permanent ID
                    proxy = proxyRegistry.getProxy(permanentId);
                    if (proxy != null) {
                        heartbeatMonitor.unregisterProxy(permanentId);
                        heartbeatMonitor.unregisterProxy(proxyId); // Also remove temp ID
                        
                        // IMMEDIATELY remove and release ID
                        boolean removed = proxyRegistry.removeProxyImmediately(permanentId);
                        
                        if (removed) {
                            LOGGER.info("Proxy {} removed - graceful shutdown", permanentId);
                            
                            // Broadcast removal
                            Map<String, Object> removalInfo = new HashMap<>();
                            removalInfo.put("proxyId", permanentId);
                            removalInfo.put("tempId", proxyId);
                            removalInfo.put("reason", notification.getReason());
                            removalInfo.put("gracefulShutdown", true);
                            removalInfo.put("timestamp", System.currentTimeMillis());
                            
                            messageBus.broadcast("registry:proxy:removed", removalInfo);
                        }
                    }
                } else {
                    LOGGER.warn("Could not find proxy to remove: {}", proxyId);
                    
                    // Still try to unregister from heartbeat
                    heartbeatMonitor.unregisterProxy(proxyId);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error handling proxy removal", e);
        }
    }
    
    public void shutdown() {
        // Cancel any ongoing registrations
        ongoingProxyRegistrations.values().forEach(future -> future.cancel(true));
        ongoingProxyRegistrations.clear();
        
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Clean up completed registration futures
     */
    private void cleanupCompletedRegistrations() {
        ongoingProxyRegistrations.entrySet().removeIf(entry -> {
            CompletableFuture<String> future = entry.getValue();
            if (future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
                if (debugMode) {
                    LOGGER.debug("Cleaning up completed registration for tempId: {}", entry.getKey());
                }
                return true;
            }
            return false;
        });
    }
    
    /**
     * Get the count of ongoing proxy registrations
     */
    public int getOngoingRegistrationCount() {
        return (int) ongoingProxyRegistrations.values().stream()
            .filter(future -> !future.isDone())
            .count();
    }
}