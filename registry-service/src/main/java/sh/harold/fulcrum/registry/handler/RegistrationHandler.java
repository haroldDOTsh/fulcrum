package sh.harold.fulcrum.registry.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Handles all registration and communication logic for the registry service.
 * This is a simplified version that handles basic Redis pub/sub communication.
 */
public class RegistrationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationHandler.class);
    private static final long REGISTRATION_TIMEOUT_SECONDS = 30;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService retryExecutor;
    private final Map<String, PendingRequest> pendingRequests;
    // State management for ongoing proxy registrations
    private final Map<String, CompletableFuture<String>> ongoingProxyRegistrations = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> serverTimeoutListeners = new CopyOnWriteArrayList<>();
    private boolean debugMode;
    private MessageBus messageBus;

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
        messageBus.subscribe(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, envelope -> {
            LOGGER.debug("Registration handler invoked");

            try {
                Object payload = envelope.payload();
                ServerRegistrationRequest request = null;

                // Try to deserialize payload to ServerRegistrationRequest
                if (payload instanceof com.fasterxml.jackson.databind.JsonNode) {
                    // Deserialize JsonNode to ServerRegistrationRequest
                    try {
                        request = objectMapper.treeToValue(
                                (com.fasterxml.jackson.databind.JsonNode) payload,
                                ServerRegistrationRequest.class
                        );
                    } catch (Exception e) {
                        LOGGER.debug("Failed to deserialize as ServerRegistrationRequest, trying Map fallback", e);
                        // Try Map conversion fallback
                        Map<String, Object> map = objectMapper.treeToValue(
                                (com.fasterxml.jackson.databind.JsonNode) payload,
                                Map.class
                        );
                        handleServerRegistrationFromMap(map);
                        return;
                    }
                } else if (payload instanceof ServerRegistrationRequest) {
                    // Already deserialized
                    request = (ServerRegistrationRequest) payload;
                } else if (payload instanceof Map) {
                    // Handle as Map
                    handleServerRegistrationFromMap((Map<String, Object>) payload);
                    return;
                }

                if (request != null) {
                    // Process the typed request directly
                    handleServerRegistrationTyped(request);
                } else {
                    LOGGER.error("Unable to process registration request - payload type: {}",
                            payload != null ? payload.getClass().getName() : "null");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle registration message", e);
            }
        });

        // Subscribe to heartbeat messages using standardized channel
        messageBus.subscribe(ChannelConstants.SERVER_HEARTBEAT, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    // Convert full envelope to JSON for heartbeat processing
                    Map<String, Object> envelopeMap = new HashMap<>();
                    envelopeMap.put("type", envelope.type());
                    envelopeMap.put("senderId", envelope.senderId());
                    envelopeMap.put("payload", objectMapper.convertValue(envelope.payload(), Map.class));
                    String json = objectMapper.writeValueAsString(envelopeMap);
                    handleServerHeartbeat(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle heartbeat", e);
                }
            }
        });

        // Subscribe to logical slot status updates
        messageBus.subscribe(ChannelConstants.REGISTRY_SLOT_STATUS, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    SlotStatusUpdateMessage update = objectMapper.treeToValue(
                            envelope.payload(), SlotStatusUpdateMessage.class);
                    serverRegistry.updateSlot(update.getServerId(), update);
                } catch (Exception e) {
                    LOGGER.warn("Failed to process slot status update", e);
                }
            }
        });

        // Subscribe to slot family advertisements
        messageBus.subscribe(ChannelConstants.REGISTRY_SLOT_FAMILY_ADVERTISEMENT, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    SlotFamilyAdvertisementMessage message = objectMapper.treeToValue(
                            envelope.payload(), SlotFamilyAdvertisementMessage.class);
                    serverRegistry.updateFamilyCapabilities(message.getServerId(), message.getFamilyCapacities());
                    serverRegistry.updateFamilyVariants(message.getServerId(), message.getFamilyVariants());
                } catch (Exception e) {
                    LOGGER.warn("Failed to process slot family advertisement", e);
                }
            }
        });

        // Subscribe to proxy unregister messages
        messageBus.subscribe(ChannelConstants.PROXY_UNREGISTER, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    String json = objectMapper.writeValueAsString(envelope.payload());
                    handleProxyUnregister(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle proxy unregister", e);
                }
            }
        });

        // Subscribe to evacuation requests
        messageBus.subscribe(ChannelConstants.SERVER_EVACUATION_REQUEST, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    String json = objectMapper.writeValueAsString(envelope.payload());
                    handleEvacuationRequest(json);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle evacuation request", e);
                }
            }
        });

        // Removed subscription to REGISTRY_SERVER_REMOVED to prevent infinite loop
        // The registry is the authoritative source for server removals and should not
        // consume its own removal notifications

        // Subscribe to proxy channel for shutdown notifications
        messageBus.subscribe(ChannelConstants.REGISTRY_PROXY_SHUTDOWN, new MessageHandler() {
            @Override
            public void handle(MessageEnvelope envelope) {
                try {
                    Object payload = envelope.payload();

                    // Handle JsonNode payloads (which come from the MessageBus deserialization)
                    if (payload instanceof com.fasterxml.jackson.databind.JsonNode) {
                        try {
                            // Convert JsonNode to ServerRemovalNotification
                            ServerRemovalNotification notification = objectMapper.treeToValue(
                                    (com.fasterxml.jackson.databind.JsonNode) payload,
                                    ServerRemovalNotification.class
                            );
                            if ("PROXY".equalsIgnoreCase(notification.serverType())) {
                                handleProxyRemoval(notification);
                            }
                        } catch (Exception e) {
                            LOGGER.error("Failed to convert JsonNode to ServerRemovalNotification", e);
                        }
                    }
                    // Check if this is already a ServerRemovalNotification
                    else if (payload instanceof ServerRemovalNotification notification) {
                        if ("PROXY".equalsIgnoreCase(notification.serverType())) {
                            handleProxyRemoval(notification);
                        }
                    } else if (payload instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) payload;
                        // Check if it has the fields of a ServerRemovalNotification
                        if (map.containsKey("serverId") && map.containsKey("serverType") && map.containsKey("reason")) {
                            ServerRemovalNotification notification = objectMapper.convertValue(
                                    payload, ServerRemovalNotification.class);

                            if ("PROXY".equalsIgnoreCase(notification.serverType())) {
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
                if (now - pending.timestamp > 10000L * (pending.retryCount + 1)) {
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
     * Handle server registration from typed request
     */
    private void handleServerRegistrationTyped(ServerRegistrationRequest apiRequest) {
        if (debugMode) {
            LOGGER.debug("Registration request received (typed)");
        }

        try {
            // Convert to internal RegistrationRequest format for processing
            RegistrationRequest request = new RegistrationRequest();
            request.setTempId(apiRequest.getTempId());
            request.setServerType(apiRequest.getServerType());
            request.setRole(apiRequest.getRole());
            request.setAddress(apiRequest.getAddress());
            request.setPort(apiRequest.getPort());
            request.setMaxCapacity(apiRequest.getMaxCapacity());
            request.setFulcrumVersion(apiRequest.getFulcrumVersion());

            processRegistrationRequest(request);

        } catch (Exception e) {
            LOGGER.error("Failed to handle typed server registration", e);
        }
    }

    /**
     * Handle server registration from Map (backward compatibility)
     */
    private void handleServerRegistrationFromMap(Map<String, Object> map) {
        if (debugMode) {
            LOGGER.debug("Registration request received (Map)");
        }

        try {
            // Convert Map to internal RegistrationRequest
            RegistrationRequest request = objectMapper.convertValue(map, RegistrationRequest.class);
            processRegistrationRequest(request);

        } catch (Exception e) {
            LOGGER.error("Failed to handle server registration from Map", e);
        }
    }

    /**
     * Handle server registration (legacy method for backward compatibility)
     */
    private void handleServerRegistration(String json) {
        if (debugMode) {
            LOGGER.debug("Registration request received (JSON string)");
        }

        try {
            // Parse as ServerRegistrationRequest
            ServerRegistrationRequest apiRequest =
                    objectMapper.readValue(json, ServerRegistrationRequest.class);
            handleServerRegistrationTyped(apiRequest);

        } catch (Exception e) {
            LOGGER.error("Failed to handle server registration from JSON", e);
        }
    }

    /**
     * Process registration request (common logic)
     */
    private void processRegistrationRequest(RegistrationRequest request) {
        try {
            String tempId = request.getTempId();
            String json = objectMapper.writeValueAsString(request);

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
            LOGGER.error("Failed to process registration request", e);
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
                if (serverId.startsWith("temp-proxy-") || serverId.startsWith("fulcrum-proxy-")) {
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
            String existingProxyId = proxyRegistry.getProxyIdByAddress(address, port);
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

            LOGGER.info("[proxy-registration] response tempId={} assignedId={} success={} message=\"{}\" channel={}",
                    tempId, proxyId, success, message, ChannelConstants.PROXY_REGISTRATION_RESPONSE);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[proxy-registration] payload={}", responsePayload);
            }

            // Send to proxy registration response channel (standardized)
            messageBus.broadcast(ChannelConstants.PROXY_REGISTRATION_RESPONSE, responsePayload);

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
            response.put("fulcrumVersion", request.getFulcrumVersion());

            // CRITICAL: Use broadcast instead of send because the backend is listening on a shared channel
            // The backend server subscribes to server registration response channel to receive responses
            String responseChannel = ChannelConstants.getServerRegistrationResponseChannel(request.getTempId());

            LOGGER.info("[server-registration] response tempId={} assignedId={} type={} addr={}:{} channel={} specificChannel={}",
                    request.getTempId(), permanentId, request.getServerType(),
                    request.getAddress(), request.getPort(),
                    ChannelConstants.SERVER_REGISTRATION_RESPONSE,
                    responseChannel);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[server-registration] payload={}", response);
            }

            // Broadcast to standardized channel
            messageBus.broadcast(ChannelConstants.SERVER_REGISTRATION_RESPONSE, response);

            // Also broadcast to server-specific channel for redundancy (standardized only)
            messageBus.broadcast(responseChannel, response);

            if (debugMode) {
                LOGGER.debug("[SENT] Server registration response via MessageBus broadcast");
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
                announcement.put("fulcrumVersion", serverInfo.getFulcrumVersion());

                // Broadcast server addition via MessageBus (standardized only)
                messageBus.broadcast(ChannelConstants.REGISTRY_SERVER_ADDED, announcement);
                if (debugMode) {
                    LOGGER.debug("[BROADCAST] Server addition via MessageBus on channel: {}", ChannelConstants.REGISTRY_SERVER_ADDED);
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
                RegisteredServerData server = serverRegistry.getServer(serverId);
                if (server == null) {
                    LOGGER.warn("Evacuation request received for unknown server {}", serverId);
                    return;
                }

                RegisteredServerData.Status previousStatus = server.getStatus();
                RegisteredServerData.Status newStatus = RegisteredServerData.Status.EVACUATING;

                // Essential log - always show evacuation requests
                LOGGER.info("Evacuation requested for server {} - Reason: {}", serverId, reason);

                // Update server status to EVACUATING
                serverRegistry.updateStatus(serverId, newStatus.name());

                RegisteredServerData refreshed = serverRegistry.getServer(serverId);
                ServerStatusChangeMessage statusChange = new ServerStatusChangeMessage();
                statusChange.setServerId(serverId);
                statusChange.setRole(refreshed != null && refreshed.getRole() != null ? refreshed.getRole() : "default");
                statusChange.setOldStatus(previousStatus != null
                        ? ServerStatusChangeMessage.Status.valueOf(previousStatus.name())
                        : ServerStatusChangeMessage.Status.RUNNING);
                statusChange.setNewStatus(ServerStatusChangeMessage.Status.EVACUATING);
                statusChange.setPlayerCount(refreshed != null ? refreshed.getPlayerCount() : 0);
                statusChange.setMaxPlayers(refreshed != null ? refreshed.getMaxCapacity() : 0);
                statusChange.setTps(refreshed != null ? refreshed.getTps() : 0.0);

                messageBus.broadcast(ChannelConstants.REGISTRY_STATUS_CHANGE, statusChange);

                // Send evacuation response
                Map<String, Object> response = new HashMap<>();
                response.put("serverId", serverId);
                response.put("success", true);
                response.put("timestamp", System.currentTimeMillis());

                messageBus.broadcast(ChannelConstants.SERVER_EVACUATION_RESPONSE, response);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to handle evacuation request", e);
        }
    }

    /**
     * Handle server removal notification from backend servers
     */
    private void handleServerRemovalNotification(ServerRemovalNotification notification) {
        String serverId = notification.serverId();
        String reason = notification.reason();

        LOGGER.info("Received server removal notification for {} - Reason: {}", serverId, reason);

        // Mark the server as stopping
        serverRegistry.updateStatus(serverId, "STOPPING");

        // Remove the server from the registry (deregisterServer handles cleanup)
        serverRegistry.deregisterServer(serverId);

        // Broadcast server removal to all proxies and other listeners
        try {
            Map<String, Object> removal = new HashMap<>();
            removal.put("serverId", serverId);
            removal.put("serverType", notification.serverType());
            removal.put("reason", reason);
            removal.put("timestamp", notification.timestamp());

            // Broadcast server removal via MessageBus (standardized only)
            messageBus.broadcast(ChannelConstants.REGISTRY_SERVER_REMOVED, removal);

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

            // Broadcast server removal via MessageBus (standardized only)
            messageBus.broadcast(ChannelConstants.REGISTRY_SERVER_REMOVED, removal);

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

            messageBus.broadcast(ChannelConstants.REGISTRY_PROXY_UNAVAILABLE, removal);
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast proxy unavailability for {}", proxyId, e);
        }
    }

    /**
     * Handle proxy graceful shutdown from heartbeat (immediately release ID)
     */
    private void handleProxyGracefulShutdown(String proxyId) {
        // Check if proxy exists before attempting removal
        if (!proxyRegistry.hasProxy(proxyId)) {
            LOGGER.info("Proxy {} already removed from registry, skipping removal", proxyId);
            return;
        }

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
                messageBus.broadcast(ChannelConstants.REGISTRY_PROXY_REMOVED, removalInfo);
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

            // Broadcast server removal via MessageBus (standardized only)
            messageBus.broadcast(ChannelConstants.REGISTRY_SERVER_REMOVED, removal);

            // Essential log - always show server timeouts
            LOGGER.warn("Server {} timed out and was removed from registry (blacklisted for 60 seconds)", serverId);

            for (Consumer<String> listener : serverTimeoutListeners) {
                try {
                    listener.accept(serverId);
                } catch (Exception listenerError) {
                    LOGGER.error("Server timeout listener threw exception", listenerError);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to broadcast server removal for {}", serverId, e);
        }
    }

    public void addServerTimeoutListener(Consumer<String> listener) {
        serverTimeoutListeners.add(listener);
    }

    /**
     * Handle proxy removal notification (ServerRemovalNotification from proxy - graceful shutdown)
     */
    private void handleProxyRemoval(ServerRemovalNotification notification) {
        try {
            String proxyId = notification.serverId();
            String reason = notification.reason() != null ? notification.reason() : "Unknown";

            LOGGER.info("Processing proxy removal request for: {} (Reason: {})", proxyId, reason);

            // Check if proxy exists before attempting removal
            if (!proxyRegistry.hasProxy(proxyId)) {
                LOGGER.debug("Proxy {} already removed, skipping removal (Reason: {})", proxyId, reason);
                return;
            }

            // Get proxy details for broadcast
            RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);

            if (proxy != null) {
                // Unregister from heartbeat monitoring
                heartbeatMonitor.unregisterProxy(proxyId);

                // IMMEDIATELY remove proxy and release ID (graceful shutdown)
                boolean removed = proxyRegistry.removeProxyImmediately(proxyId);

                if (removed) {
                    LOGGER.info("Successfully removed proxy {} from registry (Reason: {})", proxyId, reason);

                    // Broadcast removal to other services
                    Map<String, Object> removalInfo = new HashMap<>();
                    removalInfo.put("proxyId", proxy.getProxyIdString());
                    removalInfo.put("address", proxy.getAddress());
                    removalInfo.put("port", proxy.getPort());
                    removalInfo.put("reason", reason);
                    removalInfo.put("gracefulShutdown", true);
                    removalInfo.put("timestamp", System.currentTimeMillis());

                    messageBus.broadcast(ChannelConstants.REGISTRY_PROXY_REMOVED, removalInfo);
                } else {
                    LOGGER.warn("Failed to remove proxy {} from registry - removal operation failed", proxyId);
                }
            } else {
                // Try with the ID as a temporary ID first
                String permanentId = proxyRegistry.getPermanentId(proxyId);
                if (permanentId != null && proxyRegistry.hasProxy(permanentId)) {
                    // proxyId was actually a temporary ID, use permanent ID
                    proxy = proxyRegistry.getProxy(permanentId);
                    if (proxy != null) {
                        heartbeatMonitor.unregisterProxy(permanentId);
                        heartbeatMonitor.unregisterProxy(proxyId); // Also remove temp ID

                        // IMMEDIATELY remove and release ID
                        boolean removed = proxyRegistry.removeProxyImmediately(permanentId);

                        if (removed) {
                            LOGGER.info("Successfully removed proxy {} from registry (Reason: {})", permanentId, reason);

                            // Broadcast removal
                            Map<String, Object> removalInfo = new HashMap<>();
                            removalInfo.put("proxyId", permanentId);
                            removalInfo.put("tempId", proxyId);
                            removalInfo.put("reason", reason);
                            removalInfo.put("gracefulShutdown", true);
                            removalInfo.put("timestamp", System.currentTimeMillis());

                            messageBus.broadcast(ChannelConstants.REGISTRY_PROXY_REMOVED, removalInfo);
                        } else {
                            LOGGER.warn("Failed to remove proxy {} from registry - removal operation failed", permanentId);
                        }
                    }
                } else {
                    // Proxy already removed, just cleanup heartbeat monitor
                    LOGGER.debug("Proxy {} already removed, cleaning up heartbeat monitor", proxyId);
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
}
