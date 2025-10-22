package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.api.ProxyIdentifier;
import sh.harold.fulcrum.velocity.api.ServerIdentifier;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Velocity implementation of server lifecycle management.
 * Acts as the network leader, handling proxy registration and backend server approval.
 */
public class VelocityServerLifecycleFeature implements VelocityFeature {

    private static final String PROXY_PREFIX = "proxy:";
    private static final String PROXIES_KEY = "fulcrum:proxies";
    private static final String PROXY_INFO_PREFIX = "fulcrum:proxy:";
    private static final int MAX_REGISTRATION_ATTEMPTS = 5;
    private static final int RETRY_INTERVAL_SECONDS = 10;
    private final ProxyServer proxy;
    private final Logger logger;
    private final ServerLifecycleConfig config;
    private final ScheduledExecutorService scheduler;
    private final boolean developmentMode;
    private final Map<String, ServerIdentifier> backendServers = new ConcurrentHashMap<>();
    private final Map<String, Long> serverHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, ProxyAnnouncementMessage> proxyRegistry = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();  // Track when proxy started
    private MessageBus messageBus;
    private ProxyIdentifier proxyIdentifier;  // Will be updated when Registry assigns permanent ID
    private String proxyId;  // String representation for compatibility
    private ProxyAnnouncementMessage currentProxyData;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> registrationRetryTask;
    private int registrationAttempts = 0;
    private boolean registeredWithRegistry = false;
    private ServiceLocator serviceLocator;  // Store for later use in message handlers
    private ProxyConnectionHandler connectionHandler;

    public VelocityServerLifecycleFeature(ProxyServer proxy, Logger logger,
                                          ServerLifecycleConfig config,
                                          ScheduledExecutorService scheduler,
                                          boolean developmentMode) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.scheduler = scheduler;
        this.developmentMode = developmentMode;
    }

    @Override
    public String getName() {
        return "VelocityServerLifecycle";
    }

    @Override
    public int getPriority() {
        return 30; // After Identity (5), MessageBus (10), and DataAPI (20)
    }

    @Override
    public void initialize(ServiceLocator services, Logger log) {
        // Check if development mode is enabled
        if (developmentMode) {
            logger.warn("Development mode is enabled - proxy registration and heartbeats are disabled");
            // In development mode, use a simple temporary ID and skip all network operations
            this.proxyIdentifier = ProxyIdentifier.fromLegacy("dev-proxy-" + UUID.randomUUID().toString().substring(0, 8));
            this.proxyId = proxyIdentifier.getFormattedId();
            logger.info("Using development proxy ID: {}", proxyId);
            return; // Skip all initialization in development mode
        }

        this.serviceLocator = services;  // Store for use in message handlers
        this.messageBus = services.getRequiredService(MessageBus.class);

        // Get temporary proxy ID from VelocityMessageBusFeature
        services.getService(VelocityMessageBusFeature.class).ifPresentOrElse(
                messageBusFeature -> {
                    String tempId = messageBusFeature.getCurrentProxyId();
                    this.proxyIdentifier = ProxyIdentifier.fromLegacy(tempId);
                    this.proxyId = tempId;  // Keep legacy format until Registry assigns permanent ID
                    logger.info("Using temporary proxy ID from MessageBusFeature: {}", proxyId);
                },
                () -> {
                    // Generate temporary ID if MessageBusFeature is not available
                    String tempId = "temp-proxy-" + UUID.randomUUID();
                    this.proxyIdentifier = ProxyIdentifier.fromLegacy(tempId);
                    this.proxyId = tempId;  // Keep legacy format until Registry assigns permanent ID
                    logger.warn("MessageBusFeature not available, generated temporary ID: {}", proxyId);
                }
        );

        // DO NOT register in Redis yet - wait for Registry response
        logger.info("Waiting for Registry Service to assign permanent proxy ID...");

        // CRITICAL: Setup message handlers BEFORE sending registration
        // This ensures we're ready to receive the response
        setupMessageHandlers();

        // NOW send registration to Registry Service and start retry mechanism
        // The response handler is already subscribed and ready
        sendProxyRegistrationToRegistry();

        // Start cleanup task immediately (heartbeat will start after registration)
        startCleanupTask();

        // DO NOT start heartbeat until registered with Registry Service
        logger.info("Heartbeat will start after successful registration with Registry Service");

        // Register connection handler for when no backend servers are available
        connectionHandler = new ProxyConnectionHandler(proxy, proxyId, logger, this);

        // Get the plugin instance from service locator to register event
        services.getService(FulcrumVelocityPlugin.class).ifPresent(plugin -> {
            proxy.getEventManager().register(plugin, connectionHandler);
            logger.info("Registered ProxyConnectionHandler for handling player connections without backend servers");
        });

        // Send announcement requesting backend servers to register
        sendRegistrationRequest();

        logger.info("VelocityServerLifecycleFeature initialized - Proxy ID: {}", proxyId);
    }

    private void sendProxyRegistrationToRegistry() {
        // Skip registration in development mode
        if (developmentMode) {
            logger.info("Development mode - skipping proxy registration to Registry");
            return;
        }

        try {
            // CRITICAL: Log the exact ID being used for registration
            logger.info("[REGISTRATION] Preparing to register with ID: {} (is temp: {})",
                    proxyId, proxyId.startsWith("temp-"));

            // Use the SAME ServerRegistrationRequest that backend servers use
            ServerRegistrationRequest request = new ServerRegistrationRequest(
                    proxyId,      // tempId
                    "proxy",      // serverType
                    config.getHardCap()  // maxCapacity
            );

            // Set additional fields
            request.setRole("proxy");
            request.setAddress(proxy.getBoundAddress().getHostString());
            request.setPort(proxy.getBoundAddress().getPort());

            // Use the standardized channel for registration
            messageBus.broadcast(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, request);
            logger.info("[ATTEMPT {}/{}] Sent proxy registration to Registry Service with tempId: {}",
                    registrationAttempts, MAX_REGISTRATION_ATTEMPTS, proxyId);
            logger.debug("Registration request: tempId={}, type={}, role={}, address={}:{}, capacity={}",
                    request.getTempId(), request.getServerType(), request.getRole(),
                    request.getAddress(), request.getPort(), request.getMaxCapacity());

            // IMPORTANT: No heartbeat should be sent until we receive the permanent ID
            logger.info("[REGISTRATION] No heartbeat will be sent until permanent ID is received");

            // Schedule retry if no response is received
            scheduleRegistrationRetry();

        } catch (Exception e) {
            logger.error("Failed to send proxy registration to Registry Service", e);
            scheduleRegistrationRetry();
        }
    }

    private void scheduleRegistrationRetry() {
        // Cancel any existing retry task
        if (registrationRetryTask != null && !registrationRetryTask.isDone()) {
            registrationRetryTask.cancel(false);
        }

        // Increment BEFORE checking (fixes off-by-one error)
        registrationAttempts++;

        // Check if we've exceeded max attempts
        if (registrationAttempts > MAX_REGISTRATION_ATTEMPTS) {
            logger.error("[CRITICAL] Failed to register with Registry Service after {} attempts", MAX_REGISTRATION_ATTEMPTS);
            shutdownDueToRegistrationFailure();
            return;
        }

        // Schedule retry
        registrationRetryTask = scheduler.schedule(() -> {
            if (!registeredWithRegistry) {
                logger.warn("[RETRY] No registration response received, attempting retry {}/{}",
                        registrationAttempts, MAX_REGISTRATION_ATTEMPTS);
                sendProxyRegistrationToRegistry();
            }
        }, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void shutdownDueToRegistrationFailure() {
        logger.error("============================================");
        logger.error("CRITICAL: PROXY SHUTTING DOWN");
        logger.error("============================================");
        logger.error("Failed to register with Registry Service after {} attempts", MAX_REGISTRATION_ATTEMPTS);
        logger.error("The Registry Service is either:");
        logger.error("  1. Not running - please start the Registry Service");
        logger.error("  2. Not accessible - check Redis connection");
        logger.error("  3. Rejecting this proxy - check Registry logs");
        logger.error("");
        logger.error("Proxies MUST be registered with the central Registry Service");
        logger.error("to receive permanent IDs and function properly.");
        logger.error("============================================");

        // Give time for logs to be written
        scheduler.schedule(() -> {
            // Shutdown the proxy
            proxy.shutdown();
        }, 2, TimeUnit.SECONDS);
    }

    private void registerSelfInRedis() {
        // Only register AFTER getting permanent ID from Registry
        if (!registeredWithRegistry) {
            logger.warn("Cannot register - not yet registered with Registry Service");
            return;
        }

        // Create our proxy announcement with simplified capacity values
        String address = proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort();

        // Extract proxy index from ID
        int proxyIndex = extractProxyIndex(proxyId);

        currentProxyData = new ProxyAnnouncementMessage(
                proxyId,
                proxyIndex,
                config.getHardCap(),
                config.getSoftCap(),
                proxy.getPlayerCount()
        );

        // Store in local registry
        proxyRegistry.put(proxyId, currentProxyData);

        // Send proxy registration info via MessageBus
        Map<String, Object> proxyRegistration = new HashMap<>();
        proxyRegistration.put("proxyId", proxyId);
        proxyRegistration.put("address", address);
        proxyRegistration.put("type", "PROXY");
        proxyRegistration.put("hardCap", config.getHardCap());
        proxyRegistration.put("softCap", config.getSoftCap());
        proxyRegistration.put("currentPlayerCount", proxy.getPlayerCount());

        // Publish proxy registration info for other services
        messageBus.broadcast(ChannelConstants.FULCRUM_PROXY_REGISTERED, proxyRegistration);
        logger.info("Published proxy registration with permanent ID: {}", proxyId);

        // Send announcement to network
        messageBus.broadcast(ChannelConstants.PROXY_ANNOUNCEMENT, currentProxyData);
    }

    private void setupMessageHandlers() {
        // NOTE: Proxy no longer handles server registration directly
        // The Registry Service handles all registrations and broadcasts updates

        // Only use MessageBus subscription for proxy registration responses
        // Remove direct Redis listener to avoid duplicate processing

        // Listen for server additions from Registry Service
        messageBus.subscribe(ChannelConstants.REGISTRY_SERVER_ADDED, envelope -> {
            logger.info("=== SERVER ADDED BY REGISTRY ===");
            Object payload = envelope.payload();

            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> serverInfo = null;

                if (payload instanceof JsonNode) {
                    serverInfo = mapper.treeToValue((JsonNode) payload, Map.class);
                } else if (payload instanceof Map) {
                    serverInfo = (Map<String, Object>) payload;
                } else if (payload instanceof String) {
                    // Try parsing as JSON string
                    serverInfo = mapper.readValue((String) payload, Map.class);
                }

                if (serverInfo != null) {
                    String serverId = (String) serverInfo.get("serverId");
                    String serverType = (String) serverInfo.get("serverType");
                    String role = (String) serverInfo.getOrDefault("role", "default");
                    String address = (String) serverInfo.get("address");
                    Integer port = (Integer) serverInfo.get("port");
                    Integer maxCapacity = (Integer) serverInfo.get("maxCapacity");

                    logger.info("Registry added server: {} ({}:{}) - Type: {}, Role: {}, Capacity: {}",
                            serverId, address, port, serverType, role, maxCapacity);

                    // Store server info
                    ServerIdentifier serverIdentifier = new BackendServerIdentifier(
                            serverId, serverType, role, address, port, maxCapacity
                    );
                    backendServers.put(serverId, serverIdentifier);
                    serverHeartbeats.put(serverId, System.currentTimeMillis());

                    // Add to Velocity
                    addServerToVelocity(serverId, address, port);
                }
            } catch (Exception e) {
                logger.error("Failed to process server addition from Registry", e);
            }
        });

        // Listen for server removals from Registry Service
        messageBus.subscribe(ChannelConstants.REGISTRY_SERVER_REMOVED, envelope -> {
            logger.info("=== SERVER REMOVED BY REGISTRY ===");
            Object payload = envelope.payload();

            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> removal = null;

                if (payload instanceof JsonNode) {
                    removal = mapper.treeToValue((JsonNode) payload, Map.class);
                } else if (payload instanceof Map) {
                    removal = (Map<String, Object>) payload;
                } else if (payload instanceof String) {
                    // Try parsing as JSON string
                    removal = mapper.readValue((String) payload, Map.class);
                }

                if (removal != null) {
                    String serverId = (String) removal.get("serverId");
                    logger.info("Registry removed server: {}", serverId);

                    // Remove from local tracking
                    backendServers.remove(serverId);
                    serverHeartbeats.remove(serverId);

                    // Remove from Velocity
                    proxy.getServer(serverId).ifPresent(rs -> {
                        proxy.unregisterServer(rs.getServerInfo());
                        logger.info("Unregistered server from Velocity: {}", serverId);
                    });
                }
            } catch (Exception e) {
                logger.error("Failed to process server removal from Registry", e);
            }
        });

        // Handle server removal notifications (for evacuation scenarios)
        messageBus.subscribe(ChannelConstants.SERVER_EVACUATION_REQUEST, envelope -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                ServerRemovalNotification notification = null;
                Object payload = envelope.payload();

                if (payload instanceof JsonNode) {
                    notification = mapper.treeToValue((JsonNode) payload, ServerRemovalNotification.class);
                } else if (payload instanceof ServerRemovalNotification) {
                    notification = (ServerRemovalNotification) payload;
                }

                if (notification != null) {
                    handleServerRemoval(notification);
                }
            } catch (Exception e) {
                logger.error("Failed to process server removal notification", e);
            }
        });

        // Handle server heartbeats
        messageBus.subscribe(ChannelConstants.SERVER_HEARTBEAT, envelope -> {
            Object payload = envelope.payload();
            ServerHeartbeatMessage heartbeat = null;

            // Handle ObjectNode from deserialization
            if (payload instanceof JsonNode) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    heartbeat = mapper.treeToValue((JsonNode) payload, ServerHeartbeatMessage.class);
                } catch (Exception e) {
                    logger.warn("Failed to deserialize ServerHeartbeatMessage from ObjectNode: {}", e.getMessage());
                    return;
                }
            } else if (payload instanceof ServerHeartbeatMessage) {
                heartbeat = (ServerHeartbeatMessage) payload;
            } else {
                return;
            }

            String serverId = heartbeat.getServerId();
            serverHeartbeats.put(serverId, System.currentTimeMillis());
            updateServerCapacity(serverId, heartbeat.getPlayerCount());

            // Update metrics in connection handler for optimal selection
            if (connectionHandler != null) {
                connectionHandler.updateServerMetrics(
                        serverId,
                        heartbeat.getRole(),
                        heartbeat.getPlayerCount(),
                        heartbeat.getMaxCapacity(),
                        heartbeat.getTps()
                );
            }

            logger.debug("Heartbeat from server {}: {} players, TPS: {}",
                    serverId, heartbeat.getPlayerCount(), heartbeat.getTps());
        });

        // Handle server announcements (post-approval)
        messageBus.subscribe(ChannelConstants.SERVER_ANNOUNCEMENT, envelope -> {
            Object payload = envelope.payload();
            ServerAnnouncementMessage announcement = null;

            // Handle ObjectNode from deserialization
            if (payload instanceof JsonNode) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    announcement = mapper.treeToValue((JsonNode) payload, ServerAnnouncementMessage.class);
                } catch (Exception e) {
                    logger.warn("Failed to deserialize ServerAnnouncementMessage from ObjectNode: {}", e.getMessage());
                    return;
                }
            } else if (payload instanceof ServerAnnouncementMessage) {
                announcement = (ServerAnnouncementMessage) payload;
            } else {
                return;
            }

            // Update backend server info
            ServerIdentifier serverInfo = new BackendServerIdentifier(
                    announcement.getServerId(),
                    announcement.getServerType(),
                    announcement.getRole(),
                    announcement.getAddress(),
                    announcement.getPort(),
                    announcement.getCapacity()
            );
            backendServers.put(announcement.getServerId(), serverInfo);
            serverHeartbeats.put(announcement.getServerId(), System.currentTimeMillis());

            logger.debug("Registered backend server: {} - Type: {}, Capacity: {}",
                    announcement.getServerId(), announcement.getServerType(),
                    announcement.getCapacity());
        });

        // Handle proxy registration response from Registry Service via MessageBus
        messageBus.subscribe(ChannelConstants.PROXY_REGISTRATION_RESPONSE, envelope -> {
            logger.info("[PROXY RESPONSE RECEIVED] Received proxy registration response on channel: {}",
                    ChannelConstants.PROXY_REGISTRATION_RESPONSE);

            try {
                Object payload = envelope.payload();
                Map<String, Object> response = null;

                if (payload instanceof JsonNode) {
                    ObjectMapper mapper = new ObjectMapper();
                    response = mapper.treeToValue((JsonNode) payload, Map.class);
                } else if (payload instanceof Map) {
                    response = (Map<String, Object>) payload;
                } else if (payload instanceof String) {
                    ObjectMapper mapper = new ObjectMapper();
                    response = mapper.readValue((String) payload, Map.class);
                }

                if (response != null) {
                    logger.info("[PROXY RESPONSE] Processing response: tempId={}, proxyId={}, success={}",
                            response.get("tempId"), response.get("proxyId"), response.get("success"));
                    handleProxyRegistrationResponse(response);
                } else {
                    logger.error("Failed to extract response from payload");
                }
            } catch (Exception e) {
                logger.error("Failed to process proxy registration response", e);
            }
        });

        // ALSO listen on the backward-compatible channel
        messageBus.subscribe("fulcrum.registry.registration.response", envelope -> {
            logger.info("[PROXY RESPONSE RECEIVED] Received proxy registration response on LEGACY channel");

            try {
                Object payload = envelope.payload();
                Map<String, Object> response = null;

                if (payload instanceof JsonNode) {
                    ObjectMapper mapper = new ObjectMapper();
                    response = mapper.treeToValue((JsonNode) payload, Map.class);
                } else if (payload instanceof Map) {
                    response = (Map<String, Object>) payload;
                } else if (payload instanceof String) {
                    ObjectMapper mapper = new ObjectMapper();
                    response = mapper.readValue((String) payload, Map.class);
                }

                if (response != null) {
                    // Only process if it has a proxyId (proxy-specific response)
                    if (response.containsKey("proxyId") && response.get("proxyId") != null) {
                        logger.info("[PROXY RESPONSE] Processing response from legacy channel: tempId={}, proxyId={}, success={}",
                                response.get("tempId"), response.get("proxyId"), response.get("success"));
                        handleProxyRegistrationResponse(response);
                    }
                } else {
                    logger.error("Failed to extract response from payload");
                }
            } catch (Exception e) {
                logger.error("Failed to process proxy registration response from legacy channel", e);
            }
        });

        // Handle proxy discovery requests - for OTHER servers discovering this proxy
        messageBus.subscribe(ChannelConstants.PROXY_DISCOVERY_REQUEST, envelope -> {
            logger.debug("Proxy discovery request received");
            Object payload = envelope.payload();
            ProxyDiscoveryRequest request = null;

            // Handle ObjectNode from deserialization
            if (payload instanceof JsonNode) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    request = mapper.treeToValue((JsonNode) payload, ProxyDiscoveryRequest.class);
                } catch (Exception e) {
                    logger.warn("Failed to deserialize ProxyDiscoveryRequest from ObjectNode: {}", e.getMessage());
                    return;
                }
            } else if (payload instanceof ProxyDiscoveryRequest) {
                request = (ProxyDiscoveryRequest) payload;
            } else {
                // This might be our own registration request - ignore it
                logger.debug("Ignoring non-discovery message on proxy:discovery channel");
                return;
            }

            logger.debug("Discovery request from server: {} (type: {})", request.requesterId(), request.serverType());

            // Only respond if we have been registered with Registry Service
            if (registeredWithRegistry && proxyId != null && !proxyId.startsWith("temp-")) {
                // Create response with our proxy info
                ProxyDiscoveryResponse response = new ProxyDiscoveryResponse(proxyId);
                ProxyDiscoveryResponse.ProxyInfo proxyInfo = new ProxyDiscoveryResponse.ProxyInfo(
                        proxyId,
                        proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort(),
                        proxy.getConfiguration().getShowMaxPlayers(),
                        proxy.getPlayerCount()
                );
                response.addProxy(proxyInfo);

                messageBus.broadcast(ChannelConstants.PROXY_DISCOVERY_RESPONSE, response);
                logger.debug("Sent discovery response for proxy: {}", proxyId);
            } else {
                logger.debug("Not responding to discovery request - proxy not yet registered");
            }
        });

        // Handle registry re-registration requests (when registry restarts)
        messageBus.subscribe(ChannelConstants.REGISTRY_REREGISTRATION_REQUEST, envelope -> {
            try {
                logger.info("[RE-REGISTRATION] Registry Service requested re-registration");
                logger.info("[RE-REGISTRATION] Current proxy ID: {} (registered: {}, temp: {})",
                        proxyId, registeredWithRegistry, proxyId.startsWith("temp-"));

                // DIAGNOSTIC: Log timing information
                long timeSinceStart = System.currentTimeMillis() - startTime;
                logger.warn("[DIAGNOSTIC] Re-registration request received {} ms after proxy startup", timeSinceStart);

                if (timeSinceStart < 10000) {  // Less than 10 seconds since startup
                    logger.warn("[DIAGNOSTIC] Re-registration requested very soon after startup!");
                    logger.warn("[DIAGNOSTIC] This might cause duplicate registration if we just registered!");
                }

                // IMPORTANT: When registry restarts, we need to reset our registration state
                // The registry has lost our permanent ID, so we need to get a new one
                if (registeredWithRegistry) {
                    logger.info("[RE-REGISTRATION] Was previously registered, resetting state for new registration");
                    logger.warn("[DIAGNOSTIC] RESETTING registration state - will register again!");
                    registeredWithRegistry = false;
                    registrationAttempts = 0;  // Reset attempts counter
                }

                logger.warn("[DIAGNOSTIC] About to send ANOTHER registration request!");
                // Re-send our proxy registration
                sendProxyRegistrationToRegistry();

                // DON'T send heartbeat here - wait for new permanent ID from registry
                // The old permanent ID is no longer valid after registry restart
                logger.info("[RE-REGISTRATION] Registration request sent, waiting for new permanent ID assignment");
            } catch (Exception e) {
                logger.error("Failed to handle re-registration request", e);
            }
        });

        // Handle targeted re-registration request for this specific proxy
        String reregisterChannel = "proxy:" + proxyId + ":reregister";
        messageBus.subscribe(reregisterChannel, envelope -> {
            try {
                logger.info("[TARGETED RE-REG] Registry requested targeted re-registration for this proxy");
                logger.info("[TARGETED RE-REG] Current proxy ID: {} (registered: {})",
                        proxyId, registeredWithRegistry);

                // Check if we're using temp ID - this subscription might be stale
                if (proxyId.startsWith("temp-")) {
                    logger.warn("[TARGETED RE-REG] Still using temp ID, ignoring targeted re-registration");
                    return;
                }

                // Reset registration state for re-registration
                if (registeredWithRegistry) {
                    logger.info("[TARGETED RE-REG] Resetting registration state");
                    registeredWithRegistry = false;
                    registrationAttempts = 0;
                }

                sendProxyRegistrationToRegistry();

                // Don't send heartbeat - wait for permanent ID
                logger.info("[TARGETED RE-REG] Registration request sent, waiting for permanent ID");
            } catch (Exception e) {
                logger.error("Failed to handle targeted re-registration request", e);
            }
        });

        // Handle server status change messages from registry
        messageBus.subscribe(ChannelConstants.REGISTRY_STATUS_CHANGE, envelope -> {
            Object payload = envelope.payload();
            ServerStatusChangeMessage statusChange = null;

            // Handle ObjectNode from deserialization
            if (payload instanceof JsonNode) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    statusChange = mapper.treeToValue((JsonNode) payload, ServerStatusChangeMessage.class);
                } catch (Exception e) {
                    logger.warn("Failed to deserialize ServerStatusChangeMessage: {}", e.getMessage());
                    return;
                }
            } else if (payload instanceof ServerStatusChangeMessage) {
                statusChange = (ServerStatusChangeMessage) payload;
            } else {
                return;
            }

            String serverId = statusChange.getServerId();

            logger.info("Server {} status changed: {} -> {}",
                    serverId, statusChange.getOldStatus(), statusChange.getNewStatus());

            // Update metrics in connection handler
            if (connectionHandler != null) {
                if (statusChange.getNewStatus() == ServerStatusChangeMessage.Status.AVAILABLE) {
                    connectionHandler.updateServerMetrics(
                            serverId,
                            statusChange.getRole(),
                            statusChange.getPlayerCount(),
                            statusChange.getMaxPlayers(),
                            statusChange.getTps()
                    );
                } else if (statusChange.getNewStatus() == ServerStatusChangeMessage.Status.DEAD) {
                    connectionHandler.removeServerMetrics(serverId);
                }
            }
        });
    }

    // Removed handleServerRegistration method - Registry Service handles this now

    /**
     * Add backend server to Velocity's server list
     */
    private void addServerToVelocity(String serverId, String address, int port) {
        logger.info("=== DEBUG: ADDING SERVER TO VELOCITY ===");
        logger.info("Server ID: {}", serverId);
        logger.info("Address: {}:{}", address, port);

        try {
            // Debug: Check current servers before registration
            logger.info("Current servers in Velocity BEFORE registration:");
            proxy.getAllServers().forEach(server -> {
                logger.info("  - {} at {}", server.getServerInfo().getName(),
                        server.getServerInfo().getAddress());
            });

            InetSocketAddress serverAddress = InetSocketAddress.createUnresolved(address, port);
            logger.info("Created InetSocketAddress: {}", serverAddress);

            ServerInfo serverInfo = new ServerInfo(serverId, serverAddress);
            logger.info("Created ServerInfo: name={}, address={}",
                    serverInfo.getName(), serverInfo.getAddress());

            RegisteredServer registeredServer = proxy.registerServer(serverInfo);

            if (registeredServer != null) {
                logger.info("Successfully registered server in Velocity: {} at {}:{}", serverId, address, port);
                logger.info("RegisteredServer object: {}", registeredServer);
                logger.info("RegisteredServer info: name={}, address={}",
                        registeredServer.getServerInfo().getName(),
                        registeredServer.getServerInfo().getAddress());
            } else {
                logger.warn("Failed to register server in Velocity (returned null): {} at {}:{}",
                        serverId, address, port);
            }

            // Debug: Check current servers after registration
            logger.info("Current servers in Velocity AFTER registration:");
            proxy.getAllServers().forEach(server -> {
                logger.info("  - {} at {}", server.getServerInfo().getName(),
                        server.getServerInfo().getAddress());
            });

            // Debug: Try to retrieve the server we just added
            proxy.getServer(serverId).ifPresentOrElse(
                    server -> logger.info("Server {} successfully retrievable from Velocity", serverId),
                    () -> logger.error("Server {} NOT retrievable from Velocity after registration!", serverId)
            );

            logger.info("Total servers registered in Velocity: {}", proxy.getAllServers().size());

            // Dynamic server registration successful - ProxyConnectionHandler will handle player connections
            logger.info("Server '{}' dynamically registered and available for player connections", serverId);

        } catch (Exception e) {
            logger.error("Exception while adding server to Velocity: {} at {}:{}",
                    serverId, address, port, e);
            e.printStackTrace();
        }

        logger.info("=== END DEBUG: ADDING SERVER TO VELOCITY ===");
    }

    // Removed validateServer method - Registry Service handles validation now

    private void updateServerCapacity(String serverId, int currentCapacity) {
        ServerIdentifier server = backendServers.get(serverId);
        if (server instanceof BackendServerIdentifier) {
            ((BackendServerIdentifier) server).setCurrentCapacity(currentCapacity);
        }
    }

    private void startHeartbeat() {
        // Skip heartbeat in development mode
        if (developmentMode) {
            logger.info("Development mode - skipping proxy heartbeat");
            return;
        }

        // Only start heartbeat if registered with Registry
        if (!registeredWithRegistry) {
            logger.warn("[HEARTBEAT] Cannot start heartbeat - not yet registered with Registry Service");
            return;
        }

        // Check if heartbeat is already running
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            logger.info("[HEARTBEAT] Heartbeat task is already running, not starting duplicate");
            return;
        }

        // CRITICAL: Validate we have a permanent ID before starting heartbeat
        if (proxyId.startsWith("temp-")) {
            logger.error("[HEARTBEAT] CRITICAL: Attempted to start heartbeat with temporary ID: {}. ABORTING!", proxyId);
            logger.error("[HEARTBEAT] This should never happen - check registration flow!");
            return;  // Don't start heartbeat with temp ID
        }

        logger.info("[HEARTBEAT] Starting proxy heartbeat task with permanent ID: {} (interval: {} seconds)",
                proxyId, config.getHeartbeatInterval());

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // CRITICAL: Get current proxy ID each time - do NOT cache it
                String currentProxyId = this.proxyId;
                logger.trace("Heartbeat executing with current proxy ID: {}", currentProxyId);

                // Safety check - should never happen after registration
                if (currentProxyId.startsWith("temp-")) {
                    logger.error("Heartbeat attempted with temporary ID: {}. Skipping this heartbeat.", currentProxyId);
                    return;
                }

                // Update current proxy capacity
                int currentPlayers = proxy.getPlayerCount();
                int proxyIndex = extractProxyIndex(currentProxyId);

                // Update our proxy data with simplified capacity info
                currentProxyData = new ProxyAnnouncementMessage(
                        currentProxyId,
                        proxyIndex,
                        config.getHardCap(),
                        config.getSoftCap(),
                        currentPlayers
                );

                // Send heartbeat to Registry Service via MessageBus
                // Registry Service detects proxy heartbeats by the "fulcrum-proxy-" prefix
                ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage(currentProxyId, "PROXY");
                heartbeat.setPlayerCount(currentPlayers);
                heartbeat.setMaxCapacity(config.getHardCap());
                heartbeat.setTimestamp(System.currentTimeMillis());
                heartbeat.setTps(20.0); // Proxies don't have TPS but send default
                heartbeat.setRole("proxy");

                // Send heartbeat via MessageBus - use standardized channel
                messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, heartbeat);
                logger.debug("Sent heartbeat for proxy: {} (permanent: {})", currentProxyId, !currentProxyId.startsWith("temp-"));

                // Log capacity warnings
                if (config.isAtHardCapacity(currentPlayers)) {
                    logger.warn("Proxy at HARD capacity: {}/{}", currentPlayers, config.getHardCap());
                } else if (config.isAtSoftCapacity(currentPlayers)) {
                    logger.info("Proxy at soft capacity: {}/{}", currentPlayers, config.getSoftCap());
                }

                // Send proxy status update via MessageBus
                Map<String, Object> statusUpdate = new HashMap<>();
                statusUpdate.put("proxyId", currentProxyId);
                statusUpdate.put("currentPlayerCount", currentPlayers);
                statusUpdate.put("lastHeartbeat", System.currentTimeMillis());
                messageBus.broadcast(ChannelConstants.FULCRUM_PROXY_STATUS, statusUpdate);

                logger.debug("Sent proxy heartbeat - ID: {}, Current players: {}", currentProxyId, currentPlayers);
            } catch (Exception e) {
                logger.error("Error in heartbeat task", e);
            }
        }, config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.SECONDS);
    }

    private void startCleanupTask() {
        cleanupTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Check for stale backend servers
                long now = System.currentTimeMillis();
                long timeout = config.getTimeoutSeconds() * 1000L;

                serverHeartbeats.entrySet().removeIf(entry -> {
                    if (now - entry.getValue() > timeout) {
                        String serverId = entry.getKey();
                        ServerIdentifier removedServer = backendServers.remove(serverId);
                        if (removedServer != null) {
                            logger.warn("Backend server {} timed out - removing from registry",
                                    serverId);

                            // Remove from Velocity's server list
                            proxy.getServer(serverId).ifPresent(rs -> {
                                proxy.unregisterServer(rs.getServerInfo());
                                logger.info("Unregistered server from Velocity: {}", serverId);
                            });

                            // Notify network of server removal
                            messageBus.broadcast(ChannelConstants.SERVER_REMOVAL_NOTIFICATION, serverId);
                        }
                        return true;
                    }
                    return false;
                });

                // Clean up stale proxies from local registry
                // The Registry Service handles the actual cleanup
                long proxyTimeout = config.getTimeoutSeconds() * 1000L;
                proxyRegistry.entrySet().removeIf(entry -> {
                    String otherProxyId = entry.getKey();
                    if (!otherProxyId.equals(proxyId)) {
                        // Check if we haven't heard from this proxy recently
                        // In a real implementation, we'd track last heartbeat times
                        // For now, rely on Registry Service to notify us of removals
                        return false;
                    }
                    return false;
                });
            } catch (Exception e) {
                logger.error("Error in cleanup task", e);
            }
        }, config.getTimeoutSeconds(), config.getTimeoutSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() {
        // Stop scheduled tasks
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
        }
        if (registrationRetryTask != null) {
            registrationRetryTask.cancel(false);
        }

        // Send shutdown notification to Registry Service via MessageBus
        try {
            // CRITICAL: Send proper ServerRemovalNotification on registry:proxy channel
            // This is what the Registry's RegistrationHandler is listening for
            ServerRemovalNotification removalNotification = new ServerRemovalNotification(
                    proxyId,
                    "PROXY",  // serverType
                    "Proxy shutdown"
            );

            // Send on the channel that RegistrationHandler is monitoring
            messageBus.broadcast(ChannelConstants.REGISTRY_PROXY_SHUTDOWN, removalNotification);
            logger.info("Sent ServerRemovalNotification to Registry Service on registry:proxy channel");

            // Also send via proxy:announcement for backward compatibility
            Map<String, Object> announcement = new HashMap<>();
            announcement.put("proxyId", proxyId);
            announcement.put("status", "SHUTDOWN");
            announcement.put("timestamp", System.currentTimeMillis());

            messageBus.broadcast(ChannelConstants.PROXY_ANNOUNCEMENT, announcement);
            logger.info("Sent proxy shutdown announcement to Registry Service");

            // Also send via server:heartbeat with SHUTDOWN status
            ServerHeartbeatMessage shutdownHeartbeat = new ServerHeartbeatMessage(proxyId, "PROXY");
            shutdownHeartbeat.setStatus("SHUTDOWN");
            shutdownHeartbeat.setPlayerCount(0);
            shutdownHeartbeat.setMaxCapacity(0);
            shutdownHeartbeat.setTps(20.0);  // tps (not applicable for proxy, using default)
            shutdownHeartbeat.setTimestamp(System.currentTimeMillis());

            messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, shutdownHeartbeat);
            logger.info("Sent proxy shutdown signal via server:heartbeat channel");

            // Send removal notification for other services
            Map<String, Object> fulcrumRemoval = new HashMap<>();
            fulcrumRemoval.put("proxyId", proxyId);
            messageBus.broadcast(ChannelConstants.FULCRUM_PROXY_REMOVED, fulcrumRemoval);
            logger.info("Published proxy removal notification");
        } catch (Exception e) {
            logger.error("Failed to send shutdown notification to Registry Service", e);
        }

        // Notify servers of shutdown
        int proxyIndex = extractProxyIndex(proxyId);
        ProxyAnnouncementMessage shutdown = new ProxyAnnouncementMessage(
                proxyId,
                proxyIndex,
                0,  // Hard cap
                0,  // Soft cap
                -1  // Indicates shutdown
        );
        messageBus.broadcast(ChannelConstants.PROXY_SHUTDOWN, shutdown);
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        logger.info("VelocityServerLifecycleFeature shutdown complete");
    }

    private void handleServerRemoval(ServerRemovalNotification notification) {
        String serverId = notification.serverId();

        logger.warn("Received server removal notification for: {} (reason: {})",
                serverId, notification.reason());

        // Remove from backend servers map
        ServerIdentifier removedServer = backendServers.remove(serverId);
        serverHeartbeats.remove(serverId);

        if (removedServer != null) {
            // Unregister the server from Velocity
            try {
                RegisteredServer registeredServer = proxy.getServer(serverId).orElse(null);
                if (registeredServer != null) {
                    // Check if any players are still connected to this server
                    for (Player player : registeredServer.getPlayersConnected()) {
                        // Try to move player to a lobby server
                        Optional<RegisteredServer> lobbyServer = findAvailableLobbyServer();
                        if (lobbyServer.isPresent()) {
                            player.createConnectionRequest(lobbyServer.get()).fireAndForget();
                            player.sendMessage(Component.text("You have been moved to another server due to maintenance.")
                                    .color(NamedTextColor.YELLOW));
                        } else {
                            // No lobby available, disconnect the player
                            player.disconnect(Component.text("The server you were on is no longer available.")
                                    .color(NamedTextColor.RED));
                        }
                    }

                    // Now unregister the server
                    proxy.unregisterServer(registeredServer.getServerInfo());
                    logger.info("Unregistered server {} from Velocity after evacuation", serverId);
                }
            } catch (Exception e) {
                logger.error("Error unregistering server {}: {}", serverId, e.getMessage());
            }

            logger.info("Removed server {} from internal maps", serverId);
        } else {
            logger.debug("Server {} was not in our backend servers map", serverId);
        }
    }

    private Optional<RegisteredServer> findAvailableLobbyServer() {
        // Find an available lobby server
        for (Map.Entry<String, ServerIdentifier> entry : backendServers.entrySet()) {
            ServerIdentifier server = entry.getValue();
            String role = server.getRole();
            if (role != null && role.toLowerCase().contains("lobby")) {
                return proxy.getServer(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Send a request for all backend servers to register
     * This is called when a new proxy starts up
     */
    private void sendRegistrationRequest() {
        logger.info("Sending request for backend servers to register");
        int proxyIndex = extractProxyIndex(proxyId);
        messageBus.broadcast(ChannelConstants.PROXY_REQUEST_REGISTRATIONS,
                new ProxyAnnouncementMessage(
                        proxyId,
                        proxyIndex,
                        proxy.getConfiguration().getShowMaxPlayers(),
                        proxy.getConfiguration().getShowMaxPlayers() / 2,  // Default soft cap at 50%
                        proxy.getPlayerCount()));
    }

    /**
     * Handle proxy registration response from Registry
     */
    private synchronized void handleProxyRegistrationResponse(Map<String, Object> response) {
        // Check if already registered to prevent duplicate processing
        if (registeredWithRegistry) {
            logger.debug("Already registered, ignoring duplicate registration response");
            return;
        }

        String assignedProxyId = (String) response.get("proxyId");
        Boolean success = (Boolean) response.get("success");
        String tempId = (String) response.get("tempId");

        // Check if this response is for us
        if (tempId != null && !tempId.equals(this.proxyId)) {
            logger.debug("Response is for different proxy (tempId: {} vs our ID: {}), ignoring",
                    tempId, this.proxyId);
            return;
        }

        if (success != null && success && assignedProxyId != null) {
            // Cancel any pending retry
            if (registrationRetryTask != null && !registrationRetryTask.isDone()) {
                registrationRetryTask.cancel(false);
            }

            // Update our proxy ID with the permanent one from Registry
            String oldId = this.proxyId;

            // Parse the new permanent ID into ProxyIdentifier if it's in the new format
            if (ProxyIdentifier.isValid(assignedProxyId)) {
                this.proxyIdentifier = ProxyIdentifier.parse(assignedProxyId);
                this.proxyId = assignedProxyId;
            } else {
                // Handle legacy format from Registry (shouldn't happen with updated Registry)
                this.proxyIdentifier = ProxyIdentifier.fromLegacy(assignedProxyId);
                this.proxyId = assignedProxyId;  // Keep original for backward compatibility
            }
            registeredWithRegistry = true;

            logger.info("Proxy successfully registered with permanent ID: {} (was: {})", proxyId, oldId);

            // Update ProxyConnectionHandler with the permanent ID
            if (connectionHandler != null) {
                connectionHandler.updateProxyId(assignedProxyId, proxyIdentifier);
                logger.info("Updated ProxyConnectionHandler with permanent proxy ID");
            } else {
                logger.warn("ProxyConnectionHandler is null, cannot update proxy ID!");
            }

            // Update VelocityMessageBusFeature with the permanent ID
            if (serviceLocator != null) {
                serviceLocator.getService(VelocityMessageBusFeature.class).ifPresent(messageBusFeature -> {
                    messageBusFeature.updateProxyId(assignedProxyId);
                    logger.info("Updated MessageBusFeature with permanent proxy ID");
                });
                serviceLocator.getService(sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature.class)
                        .ifPresent(feature -> feature.onProxyIdUpdated(assignedProxyId));
            }

            // Now register in Redis with permanent ID
            registerSelfInRedis();

            // Update our proxy announcement data
            int proxyIndex = extractProxyIndex(proxyId);
            currentProxyData = new ProxyAnnouncementMessage(
                    proxyId,
                    proxyIndex,
                    config.getHardCap(),
                    config.getSoftCap(),
                    proxy.getPlayerCount()
            );

            // Final safety check before starting heartbeat
            if (this.proxyId.startsWith("temp-")) {
                logger.error("[REGISTRATION] CRITICAL: Still have temp ID after registration! Old: {}, Assigned: {}, Current: {}",
                        oldId, assignedProxyId, this.proxyId);
                // Force update
                this.proxyId = assignedProxyId;
            }

            // Send immediate test heartbeat to verify ID is working
            logger.info("[TEST HEARTBEAT] Preparing to send test heartbeat");
            logger.info("[TEST HEARTBEAT] Current proxy ID: {}", this.proxyId);
            logger.info("[TEST HEARTBEAT] Assigned proxy ID: {}", assignedProxyId);

            // Use the assigned ID directly to be absolutely sure
            ServerHeartbeatMessage testHeartbeat = new ServerHeartbeatMessage(assignedProxyId, "PROXY");
            testHeartbeat.setPlayerCount(proxy.getPlayerCount());
            testHeartbeat.setMaxCapacity(config.getHardCap());
            testHeartbeat.setTimestamp(System.currentTimeMillis());
            testHeartbeat.setTps(20.0);
            testHeartbeat.setRole("proxy");

            logger.info("[TEST HEARTBEAT] Sending test heartbeat with ID: {}", assignedProxyId);
            messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, testHeartbeat);

            // NOW start heartbeat after successful registration
            logger.info("[REGISTRATION] Starting regular heartbeat task after successful registration");
            startHeartbeat();

            // Send initial heartbeat and announcement
            messageBus.broadcast(ChannelConstants.PROXY_ANNOUNCEMENT, currentProxyData);
            logger.info("Sent initial proxy announcement with permanent ID");
        } else {
            logger.error("[FAILED] Proxy registration failed: {}", response.get("message"));
            // Retry will be handled by the scheduled retry mechanism
        }
    }

    /**
     * Get registered backend servers
     */
    public Map<String, ServerIdentifier> getBackendServers() {
        return new ConcurrentHashMap<>(backendServers);
    }

    /**
     * Gets all registered backend servers
     *
     * @return Set of all server identifiers
     */
    public Set<ServerIdentifier> getRegisteredServers() {
        return new HashSet<>(backendServers.values());
    }

    /**
     * Gets servers by role
     *
     * @param role The role to filter by (e.g., "lobby", "game", "survival")
     * @return Set of server identifiers matching the specified role
     */
    public Set<ServerIdentifier> getServersByRole(String role) {
        if (role == null || role.isEmpty()) {
            return new HashSet<>();
        }

        return backendServers.values().stream()
                .filter(server -> role.equalsIgnoreCase(server.getRole()))
                .collect(Collectors.toSet());
    }

    /**
     * Gets a specific server by ID
     *
     * @param serverId The server ID to look up
     * @return Optional containing the server identifier if found
     */
    public Optional<ServerIdentifier> getServerById(String serverId) {
        return Optional.ofNullable(backendServers.get(serverId));
    }

    /**
     * Check if a backend server is registered and active
     */
    public boolean isServerActive(String serverId) {
        return backendServers.containsKey(serverId) &&
                serverHeartbeats.containsKey(serverId);
    }

    private int extractProxyIndex(String proxyId) {
        // If we have a ProxyIdentifier, use its instance ID
        if (proxyIdentifier != null) {
            return proxyIdentifier.getInstanceId();
        }

        // Fallback: Extract index from legacy ID format: fulcrum-proxy-N
        if (proxyId != null && proxyId.startsWith("fulcrum-proxy-")) {
            try {
                String indexStr = proxyId.substring("fulcrum-proxy-".length());
                return Integer.parseInt(indexStr);
            } catch (NumberFormatException e) {
                // Fallback for overflow or special cases
                return 0;
            }
        }
        return 0;
    }

    // Removed server ID generation - Registry Service handles ID allocation now

    /**
     * Get the optimal server for a specific role
     *
     * @param role The server role (e.g., "lobby", "survival", "minigames")
     * @return The optimal server identifier, or null if none available
     */
    public String getOptimalServerByRole(String role) {
        if (role == null || role.isEmpty()) {
            logger.warn("getOptimalServerByRole called with null/empty role");
            return null;
        }

        if (connectionHandler == null) {
            // Fallback to simple selection when connection handler not available
            logger.debug("Connection handler not available, using simple selection for role: {}", role);
            return backendServers.values().stream()
                    .filter(server -> role.equalsIgnoreCase(server.getRole()))
                    .filter(server -> isServerActive(server.getServerId()))
                    .map(ServerIdentifier::getServerId)
                    .findFirst()
                    .orElse(null);
        }

        // Use the connection handler's optimal selection algorithm
        RegisteredServer optimal = connectionHandler.findOptimalServer(role);
        if (optimal != null) {
            logger.debug("Found optimal {} server: {}", role, optimal.getServerInfo().getName());
            return optimal.getServerInfo().getName();
        } else {
            logger.debug("No {} servers available", role);
            return null;
        }
    }

    /**
     * Get all available servers for a specific role
     *
     * @param role The server role
     * @return List of server identifiers
     */
    public List<String> getAllServersByRole(String role) {
        return backendServers.values().stream()
                .filter(server -> role.equalsIgnoreCase(server.getRole()))
                .filter(server -> isServerActive(server.getServerId()))
                .map(ServerIdentifier::getServerId)
                .collect(Collectors.toList());
    }

    /**
     * Get server statistics for monitoring
     *
     * @param serverId The server identifier
     * @return Map of server statistics
     */
    public Map<String, Object> getServerStats(String serverId) {
        ServerIdentifier server = backendServers.get(serverId);
        if (server == null) {
            return null;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", server.getServerId());
        stats.put("role", server.getRole());
        stats.put("type", server.getType());
        stats.put("address", server.getAddress());
        stats.put("port", server.getPort());
        stats.put("softCap", server.getSoftCap());
        stats.put("hardCap", server.getHardCap());

        Long lastHeartbeat = serverHeartbeats.get(serverId);
        if (lastHeartbeat != null) {
            stats.put("lastHeartbeat", lastHeartbeat);
            stats.put("online", System.currentTimeMillis() - lastHeartbeat < config.getTimeoutSeconds() * 1000L);
        } else {
            stats.put("online", false);
        }

        if (server instanceof BackendServerIdentifier) {
            stats.put("currentCapacity", ((BackendServerIdentifier) server).currentCapacity);
        }

        return stats;
    }

    /**
     * Implementation of ServerIdentifier for backend servers
     */
    private static class BackendServerIdentifier implements ServerIdentifier {
        private final String serverId;
        private final String type;
        private final String role;
        private final String address;
        private final int port;
        private final int capacity;
        private int currentCapacity;

        public BackendServerIdentifier(String serverId, String type, String role,
                                       String address, int port, int capacity) {
            this.serverId = serverId;
            this.type = type;
            this.role = role != null ? role : "default";
            this.address = address;
            this.port = port;
            this.capacity = capacity;
            this.currentCapacity = 0;
        }

        @Override
        public String getServerId() {
            return serverId;
        }

        @Override
        public String getRole() {
            return role;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public UUID getInstanceUuid() {
            // Generate stable UUID from serverId
            return UUID.nameUUIDFromBytes(serverId.getBytes());
        }

        @Override
        public String getAddress() {
            return address;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public int getSoftCap() {
            return (int) (capacity * 0.8);
        }

        @Override
        public int getHardCap() {
            return capacity;
        }

        @Override
        public boolean isLocal() {
            return false;
        }

        public void setCurrentCapacity(int currentCapacity) {
            this.currentCapacity = currentCapacity;
        }
    }
}
