package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification;
import sh.harold.fulcrum.api.messagebus.messages.ServerStatusChangeMessage;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;

import java.net.InetSocketAddress;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.ArrayList;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Velocity implementation of server lifecycle management.
 * Acts as the network leader, handling proxy registration and backend server approval.
 */
public class VelocityServerLifecycleFeature implements VelocityFeature {
    
    private static final String PROXY_PREFIX = "proxy:";
    private static final String PROXIES_KEY = "fulcrum:proxies";
    private static final String PROXY_INFO_PREFIX = "fulcrum:proxy:";
    
    private final ProxyServer proxy;
    private final Logger logger;
    private final ServerLifecycleConfig config;
    private final ScheduledExecutorService scheduler;
    
    private MessageBus messageBus;
    private String proxyId;  // Will be updated when Registry assigns permanent ID
    private ProxyAnnouncementMessage currentProxyData;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> registrationRetryTask;
    private int registrationAttempts = 0;
    private static final int MAX_REGISTRATION_ATTEMPTS = 5;
    private static final int RETRY_INTERVAL_SECONDS = 10;
    private boolean registeredWithRegistry = false;
    private ServiceLocator serviceLocator;  // Store for later use in message handlers
    private final Map<String, ServerIdentifier> backendServers = new ConcurrentHashMap<>();
    private final Map<String, Long> serverHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, ProxyAnnouncementMessage> proxyRegistry = new ConcurrentHashMap<>();
    private ProxyConnectionHandler connectionHandler;
    
    public VelocityServerLifecycleFeature(ProxyServer proxy, Logger logger,
                                         ServerLifecycleConfig config,
                                         ScheduledExecutorService scheduler) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.scheduler = scheduler;
    }
    
    @Override
    public String getName() {
        return "VelocityServerLifecycle";
    }
    
    @Override
    public int getPriority() {
        return 30; // After MessageBus (10) and Identity (20)
    }
    
    @Override
    public void initialize(ServiceLocator services, Logger log) {
        this.serviceLocator = services;  // Store for use in message handlers
        this.messageBus = services.getRequiredService(MessageBus.class);
        
        // Get temporary proxy ID from VelocityMessageBusFeature
        services.getService(VelocityMessageBusFeature.class).ifPresentOrElse(
            messageBusFeature -> {
                this.proxyId = messageBusFeature.getCurrentProxyId();
                logger.info("Using temporary proxy ID from MessageBusFeature: {}", proxyId);
            },
            () -> {
                // Generate temporary ID if MessageBusFeature is not available
                this.proxyId = "temp-proxy-" + UUID.randomUUID().toString();
                logger.warn("MessageBusFeature not available, generated temporary ID: {}", proxyId);
            }
        );
        
        // DO NOT register in Redis yet - wait for Registry response
        logger.info("Waiting for Registry Service to assign permanent proxy ID...");
        
        // Send registration to Registry Service and start retry mechanism
        sendProxyRegistrationToRegistry();
        
        // Setup message handlers
        setupMessageHandlers();
        
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
        try {
            // Send registration request to Registry Service using the expected format
            Map<String, Object> registrationRequest = new HashMap<>();
            registrationRequest.put("tempId", proxyId);  // Use current proxy ID (temporary)
            registrationRequest.put("serverType", "proxy");
            registrationRequest.put("role", "proxy");
            registrationRequest.put("address", proxy.getBoundAddress().getHostString());
            registrationRequest.put("port", proxy.getBoundAddress().getPort());
            registrationRequest.put("maxCapacity", config.getHardCap());
            registrationRequest.put("family", "proxy");
            
            // Use MessageBus to publish registration request
            messageBus.broadcast("registry:register", registrationRequest);
            logger.info("[ATTEMPT {}/{}] Sent proxy registration to Registry Service on channel 'registry:register' with tempId: {}",
                       registrationAttempts, MAX_REGISTRATION_ATTEMPTS, proxyId);
            logger.debug("Registration message: {}", registrationRequest);
            
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
        messageBus.broadcast("fulcrum:proxy:registered", proxyRegistration);
        logger.info("Published proxy registration with permanent ID: {}", proxyId);
        
        // Send announcement to network
        messageBus.broadcast("proxy:announce", currentProxyData);
    }
    
    private void setupMessageHandlers() {
        // NOTE: Proxy no longer handles server registration directly
        // The Registry Service handles all registrations and broadcasts updates
        
        // Only use MessageBus subscription for proxy registration responses
        // Remove direct Redis listener to avoid duplicate processing
        
        // Listen for server additions from Registry Service
        messageBus.subscribe("registry:server:added", envelope -> {
            logger.info("=== SERVER ADDED BY REGISTRY ===");
            Object payload = envelope.getPayload();
            
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
                    String family = (String) serverInfo.getOrDefault("family", "default");
                    String address = (String) serverInfo.get("address");
                    Integer port = (Integer) serverInfo.get("port");
                    Integer maxCapacity = (Integer) serverInfo.get("maxCapacity");
                    
                    logger.info("Registry added server: {} ({}:{}) - Type: {}, Family: {}, Capacity: {}",
                               serverId, address, port, serverType, family, maxCapacity);
                    
                    // Store server info
                    ServerIdentifier serverIdentifier = new BackendServerIdentifier(
                        serverId, serverType, family, address, port, maxCapacity
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
        messageBus.subscribe("registry:server:removed", envelope -> {
            logger.info("=== SERVER REMOVED BY REGISTRY ===");
            Object payload = envelope.getPayload();
            
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
        messageBus.subscribe("server.removal.notification", envelope -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                ServerRemovalNotification notification = null;
                Object payload = envelope.getPayload();
                
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
        messageBus.subscribe("server:heartbeat", envelope -> {
            Object payload = envelope.getPayload();
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
                    heartbeat.getFamily(),
                    heartbeat.getPlayerCount(),
                    heartbeat.getMaxCapacity(),
                    heartbeat.getTps(),
                    heartbeat.getResponseTime()
                );
            }
            
            logger.debug("Heartbeat from server {}: {} players, TPS: {}",
                        serverId, heartbeat.getPlayerCount(), heartbeat.getTps());
        });
        
        // Handle server announcements (post-approval)
        messageBus.subscribe("server:announce", envelope -> {
            Object payload = envelope.getPayload();
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
                announcement.getFamily(),
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
        messageBus.subscribe("proxy:registration:response", envelope -> {
            logger.info("=== PROXY REGISTRATION RESPONSE RECEIVED ===");
            try {
                Object payload = envelope.getPayload();
                Map<String, Object> response = null;
                
                if (payload instanceof JsonNode) {
                    ObjectMapper mapper = new ObjectMapper();
                    response = mapper.treeToValue((JsonNode) payload, Map.class);
                } else if (payload instanceof Map) {
                    response = (Map<String, Object>) payload;
                }
                
                if (response != null) {
                    handleProxyRegistrationResponse(response);
                }
            } catch (Exception e) {
                logger.error("Failed to process proxy registration response", e);
            }
        });
        
        // Handle proxy discovery requests - for OTHER servers discovering this proxy
        messageBus.subscribe("proxy:discovery", envelope -> {
            logger.info("=== PROXY DISCOVERY REQUEST RECEIVED ===");
            Object payload = envelope.getPayload();
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
            
            logger.info("Discovery request from server: {} (type: {})", request.getRequesterId(), request.getServerType());
            
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
                
                messageBus.broadcast("proxy:discovery:response", response);
                logger.info("Sent discovery response on channel 'proxy:discovery:response'");
                logger.info("Proxy info - ID: {}, Address: {}, Capacity: {}/{}",
                           proxyId,
                           proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort(),
                           proxy.getPlayerCount(),
                           proxy.getConfiguration().getShowMaxPlayers());
            } else {
                logger.debug("Not responding to discovery request - proxy not yet registered");
            }
        });
        
        // Handle server status change messages from registry
        messageBus.subscribe("registry:status:change", envelope -> {
            Object payload = envelope.getPayload();
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
                        statusChange.getServerFamily(),
                        statusChange.getPlayerCount(),
                        statusChange.getMaxPlayers(),
                        statusChange.getTps(),
                        statusChange.getResponseTime()
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
                server -> logger.info("✓ Server {} successfully retrievable from Velocity", serverId),
                () -> logger.error("✗ Server {} NOT retrievable from Velocity after registration!", serverId)
            );
            
            logger.info("Total servers registered in Velocity: {}", proxy.getAllServers().size());
            
            // CRITICAL: Check if server is in the try list
            checkTryListConfiguration(serverId);
            
        } catch (Exception e) {
            logger.error("Exception while adding server to Velocity: {} at {}:{}",
                        serverId, address, port, e);
            e.printStackTrace();
        }
        
        logger.info("=== END DEBUG: ADDING SERVER TO VELOCITY ===");
    }
    
    /**
     * Check if the server is in Velocity's try list and warn if not
     */
    private void checkTryListConfiguration(String serverId) {
        try {
            // Get the configuration
            com.velocitypowered.api.proxy.config.ProxyConfig config = proxy.getConfiguration();
            
            // Get the current try list
            List<String> tryServers = config.getAttemptConnectionOrder();
            logger.info("Current 'try' list from config: {}", tryServers);
            
            if (tryServers.isEmpty()) {
                logger.error("===============================================");
                logger.error("CRITICAL CONFIGURATION ISSUE DETECTED!");
                logger.error("===============================================");
                logger.error("The 'try' list in velocity.toml is EMPTY!");
                logger.error("This means players cannot connect to any servers.");
                logger.error("");
                logger.error("SOLUTION:");
                logger.error("1. Edit velocity.toml");
                logger.error("2. Under [servers] section, add:");
                logger.error("   try = [\"mega1\"]");
                logger.error("3. Restart Velocity");
                logger.error("");
                logger.error("OR for dynamic registration, add:");
                logger.error("   try = [\"lobby\", \"hub\"]");
                logger.error("===============================================");
            } else if (!tryServers.contains(serverId)) {
                logger.warn("===============================================");
                logger.warn("WARNING: Server '{}' is NOT in the try list!", serverId);
                logger.warn("Players won't be able to connect to this server initially.");
                logger.warn("They can only reach it via /server {} command.", serverId);
                logger.warn("");
                logger.warn("To fix: Add '{}' to the 'try' list in velocity.toml", serverId);
                logger.warn("===============================================");
            } else {
                logger.info("✓ Server '{}' is properly configured in the try list", serverId);
            }
        } catch (Exception e) {
            logger.error("Failed to check try list configuration", e);
        }
    }
    
    // Removed validateServer method - Registry Service handles validation now
    
    private void updateServerCapacity(String serverId, int currentCapacity) {
        ServerIdentifier server = backendServers.get(serverId);
        if (server instanceof BackendServerIdentifier) {
            ((BackendServerIdentifier) server).setCurrentCapacity(currentCapacity);
        }
    }
    
    private void startHeartbeat() {
        // Only start heartbeat if registered with Registry
        if (!registeredWithRegistry) {
            logger.warn("Cannot start heartbeat - not yet registered with Registry Service");
            return;
        }
        
        // Check if heartbeat is already running
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            logger.info("Heartbeat task is already running");
            return;
        }
        
        logger.info("Starting proxy heartbeat task with permanent ID: {} (interval: {} seconds)",
                   proxyId, config.getHeartbeatInterval());
        
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Update current proxy capacity
                int currentPlayers = proxy.getPlayerCount();
                int proxyIndex = extractProxyIndex(proxyId);
                
                // Update our proxy data with simplified capacity info
                currentProxyData = new ProxyAnnouncementMessage(
                    proxyId,
                    proxyIndex,
                    config.getHardCap(),
                    config.getSoftCap(),
                    currentPlayers
                );
                
                // Send heartbeat to Registry Service via MessageBus
                // Registry Service detects proxy heartbeats by the "fulcrum-proxy-" prefix
                ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage(proxyId, "PROXY");
                heartbeat.setPlayerCount(currentPlayers);
                heartbeat.setMaxCapacity(config.getHardCap());
                heartbeat.setTimestamp(System.currentTimeMillis());
                heartbeat.setTps(20.0); // Proxies don't have TPS but send default
                heartbeat.setFamily("proxy");
                heartbeat.setRole("proxy");
                
                // Send heartbeat via MessageBus - use colon separator to match registry subscription
                messageBus.broadcast("server:heartbeat", heartbeat);
                // logger.info("Sent proxy heartbeat for {} with {} players at {} (interval: {}s)",
                //     proxyId, currentPlayers, System.currentTimeMillis(), config.getHeartbeatInterval());
                
                // Log capacity warnings
                if (config.isAtHardCapacity(currentPlayers)) {
                    logger.warn("Proxy at HARD capacity: {}/{}", currentPlayers, config.getHardCap());
                } else if (config.isAtSoftCapacity(currentPlayers)) {
                    logger.info("Proxy at soft capacity: {}/{}", currentPlayers, config.getSoftCap());
                }
                
                // Send proxy status update via MessageBus
                Map<String, Object> statusUpdate = new HashMap<>();
                statusUpdate.put("proxyId", proxyId);
                statusUpdate.put("currentPlayerCount", currentPlayers);
                statusUpdate.put("lastHeartbeat", System.currentTimeMillis());
                messageBus.broadcast("fulcrum:proxy:status", statusUpdate);
                
                logger.debug("Sent proxy heartbeat - Current players: {}", currentPlayers);
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
                            messageBus.broadcast("server:removed", serverId);
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
            // Method 1: Send via proxy:announcement channel
            Map<String, Object> announcement = new HashMap<>();
            announcement.put("proxyId", proxyId);
            announcement.put("status", "SHUTDOWN");
            announcement.put("timestamp", System.currentTimeMillis());
            
            messageBus.broadcast("proxy:announcement", announcement);
            logger.info("Sent proxy shutdown announcement to Registry Service");
            
            // Method 2: Also send via server:heartbeat with SHUTDOWN status
            ServerHeartbeatMessage shutdownHeartbeat = new ServerHeartbeatMessage(proxyId, "PROXY");
            shutdownHeartbeat.setPlayerCount(0);
            shutdownHeartbeat.setTimestamp(System.currentTimeMillis());
            
            // Add a shutdown status field by extending the message
            Map<String, Object> shutdownMessage = new HashMap<>();
            shutdownMessage.put("serverId", proxyId);
            shutdownMessage.put("status", "SHUTDOWN");
            shutdownMessage.put("timestamp", System.currentTimeMillis());
            
            messageBus.broadcast("server:heartbeat", shutdownMessage);
            logger.info("Sent proxy shutdown signal via server:heartbeat channel");
            
            // Send removal notification
            Map<String, Object> removalNotification = new HashMap<>();
            removalNotification.put("proxyId", proxyId);
            messageBus.broadcast("fulcrum:proxy:removed", removalNotification);
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
        messageBus.broadcast("proxy:shutdown", shutdown);
        
        logger.info("VelocityServerLifecycleFeature shutdown complete");
    }
    
    private void handleServerRemoval(ServerRemovalNotification notification) {
        String serverId = notification.getServerId();
        
        logger.warn("Received server removal notification for: {} (reason: {})",
                   serverId, notification.getReason());
        
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
            String family = server.getFamily();
            if (family != null && family.toLowerCase().contains("lobby")) {
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
        messageBus.broadcast("proxy:request-registrations",
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
        
        if (success != null && success && assignedProxyId != null) {
            // Cancel any pending retry
            if (registrationRetryTask != null && !registrationRetryTask.isDone()) {
                registrationRetryTask.cancel(false);
            }
            
            // Update our proxy ID with the permanent one from Registry
            String oldId = this.proxyId;
            this.proxyId = assignedProxyId;
            registeredWithRegistry = true;
            
            logger.info("[REGISTERED] Proxy successfully registered with permanent ID: {} (was: {})", proxyId, oldId);
            
            // Update VelocityMessageBusFeature with the permanent ID
            if (serviceLocator != null) {
                serviceLocator.getService(VelocityMessageBusFeature.class).ifPresent(messageBusFeature -> {
                    messageBusFeature.updateProxyId(assignedProxyId);
                    logger.info("Updated MessageBusFeature with permanent proxy ID");
                });
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
            
            // NOW start heartbeat after successful registration
            startHeartbeat();
            
            // Send initial heartbeat and announcement
            messageBus.broadcast("proxy:announce", currentProxyData);
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
     * @return Set of all server identifiers
     */
    public Set<ServerIdentifier> getRegisteredServers() {
        return new HashSet<>(backendServers.values());
    }
    
    /**
     * Gets servers by family/role
     * @param family The family/role to filter by (e.g., "lobby", "game", "survival")
     * @return Set of server identifiers matching the specified family
     */
    public Set<ServerIdentifier> getServersByFamily(String family) {
        if (family == null || family.isEmpty()) {
            return new HashSet<>();
        }
        
        return backendServers.values().stream()
            .filter(server -> family.equalsIgnoreCase(server.getFamily()))
            .collect(Collectors.toSet());
    }
    
    /**
     * Gets a specific server by ID
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
        // Extract index from ID format: fulcrum-proxy-N
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
     * Get the optimal server for a specific family/role
     * @param family The server family (e.g., "lobby", "survival", "minigames")
     * @return The optimal server identifier, or null if none available
     */
    public String getOptimalServerByFamily(String family) {
        if (connectionHandler == null) {
            // Fallback to simple selection
            return backendServers.values().stream()
                .filter(server -> family.equalsIgnoreCase(server.getFamily()))
                .filter(server -> isServerActive(server.getServerId()))
                .map(ServerIdentifier::getServerId)
                .findFirst()
                .orElse(null);
        }
        
        RegisteredServer optimal = connectionHandler.findOptimalServer(family);
        return optimal != null ? optimal.getServerInfo().getName() : null;
    }
    
    /**
     * Get all available servers for a specific family/role
     * @param family The server family
     * @return List of server identifiers
     */
    public List<String> getAllServersByFamily(String family) {
        return backendServers.values().stream()
            .filter(server -> family.equalsIgnoreCase(server.getFamily()))
            .filter(server -> isServerActive(server.getServerId()))
            .map(ServerIdentifier::getServerId)
            .collect(Collectors.toList());
    }
    
    /**
     * Get server statistics for monitoring
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
        stats.put("family", server.getFamily());
        stats.put("type", server.getType());
        stats.put("address", server.getAddress());
        stats.put("port", server.getPort());
        stats.put("softCap", server.getSoftCap());
        stats.put("hardCap", server.getHardCap());
        
        Long lastHeartbeat = serverHeartbeats.get(serverId);
        if (lastHeartbeat != null) {
            stats.put("lastHeartbeat", lastHeartbeat);
            stats.put("online", System.currentTimeMillis() - lastHeartbeat < config.getTimeoutSeconds() * 1000);
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
        private final String family;
        private final String address;
        private final int port;
        private final int capacity;
        private int currentCapacity;
        
        public BackendServerIdentifier(String serverId, String type, String family,
                                      String address, int port, int capacity) {
            this.serverId = serverId;
            this.type = type;
            this.family = family != null ? family : "default";
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
        public String getFamily() {
            return family;
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
            return (int)(capacity * 0.8);
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