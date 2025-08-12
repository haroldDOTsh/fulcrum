package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityRedisMessageBus;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocitySimpleMessageBus;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;

import java.net.InetSocketAddress;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
    private String proxyId;
    private ProxyAnnouncementMessage currentProxyData;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> cleanupTask;
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
        this.messageBus = services.getRequiredService(MessageBus.class);
        
        // Get proxy ID and index from VelocityMessageBusFeature
        // The MessageBus feature allocates slots using Redis
        services.getService(VelocityMessageBusFeature.class).ifPresentOrElse(
            messageBusFeature -> this.proxyId = messageBusFeature.getProxyId(),
            () -> this.proxyId = "fulcrum-proxy-1" // Fallback if MessageBusFeature is not available
        );
        
        // Register proxy
        registerSelfInRedis();
        
        // Setup message handlers
        setupMessageHandlers();
        
        // Start heartbeat and cleanup tasks
        startHeartbeat();
        startCleanupTask();
        
        // Register connection handler for when no backend servers are available
        connectionHandler = new ProxyConnectionHandler(proxy, proxyId, logger);
        
        // Get the plugin instance from service locator to register event
        services.getService(FulcrumVelocityPlugin.class).ifPresent(plugin -> {
            proxy.getEventManager().register(plugin, connectionHandler);
            logger.info("Registered ProxyConnectionHandler for handling player connections without backend servers");
        });
        
        // Send announcement requesting backend servers to register
        sendRegistrationRequest();
        
        logger.info("VelocityServerLifecycleFeature initialized - Proxy ID: {}", proxyId);
    }
    
    private void registerSelfInRedis() {
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
        
        // Self-registration in Redis SET (proxies are leaders, no approval needed)
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            // Add to proxies set
            commands.sadd(PROXIES_KEY, proxyId);
            
            // Store proxy info with TTL
            String proxyKey = PROXY_INFO_PREFIX + proxyId;
            Map<String, String> proxyData = new HashMap<>();
            proxyData.put("address", address);
            proxyData.put("type", "PROXY");
            proxyData.put("hardCap", String.valueOf(config.getHardCap()));
            proxyData.put("softCap", String.valueOf(config.getSoftCap()));
            proxyData.put("currentPlayerCount", String.valueOf(proxy.getPlayerCount()));
            commands.hset(proxyKey, proxyData);
            commands.expire(proxyKey, config.getTimeoutSeconds());
            
            logger.info("Registered proxy in Redis: {}", proxyId);
        }
        
        // Store in local registry
        proxyRegistry.put(proxyId, currentProxyData);
        
        // Send announcement to network
        messageBus.broadcast("proxy:announce", currentProxyData);
    }
    
    private void setupMessageHandlers() {
        // Handle backend server registration requests
        messageBus.subscribe("proxy:register", envelope -> {
            logger.info("=== RECEIVED REGISTRATION REQUEST ===");
            logger.info("Channel: proxy:register");
            Object payload = envelope.getPayload();
            ServerRegistrationRequest req = null;
            
            // Handle ObjectNode from deserialization
            if (payload instanceof JsonNode) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    req = mapper.treeToValue((JsonNode) payload, ServerRegistrationRequest.class);
                } catch (Exception e) {
                    logger.warn("Failed to deserialize ServerRegistrationRequest from ObjectNode: {}", e.getMessage());
                    return;
                }
            } else if (payload instanceof ServerRegistrationRequest) {
                req = (ServerRegistrationRequest) payload;
            } else {
                logger.warn("Invalid payload type on proxy:register channel: {}",
                           payload != null ? payload.getClass().getName() : "null");
                return;
            }
            
            logger.info("Server ID: {}", req.getTempId());
            logger.info("Server Type: {}", req.getServerType());
            logger.info("Capacity: {}", req.getMaxCapacity());
            handleServerRegistration(req);
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
            logger.debug("Heartbeat from server {}: {} players", serverId, heartbeat.getPlayerCount());
        });
        
        // Also handle the new message format with dot notation for backward compatibility
        messageBus.subscribe("server.heartbeat", envelope -> {
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
            logger.debug("Heartbeat (dot format) from server {}: {} players", serverId, heartbeat.getPlayerCount());
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
        
        // Handle proxy discovery requests
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
                logger.warn("Invalid payload type on proxy:discovery channel: {}",
                           payload != null ? payload.getClass().getName() : "null");
                return;
            }
            
            logger.info("From server: {} (type: {})", request.getRequesterId(), request.getServerType());
            
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
        });
    }
    
    private void handleServerRegistration(ServerRegistrationRequest request) {
        logger.info("=== PROCESSING SERVER REGISTRATION ===");
        logger.info("Server Details:");
        logger.info("  - Server ID: {}", request.getTempId());
        logger.info("  - Type: {}", request.getServerType());
        logger.info("  - Role: {}", request.getRole());
        logger.info("  - Capacity: {}", request.getMaxCapacity());
        logger.info("  - Address: {}:{}", request.getAddress(), request.getPort());
        
        // Validate server
        boolean approved = validateServer(request);
        String rejectionReason = null;
        
        if (!approved) {
            if (request.getTempId() == null) {
                rejectionReason = "Server ID is required";
            } else if (request.getServerType() == null || request.getServerType().isEmpty()) {
                rejectionReason = "Server type is required";
            } else if (request.getMaxCapacity() <= 0) {
                rejectionReason = "Invalid server capacity";
            } else if (request.getAddress() == null || request.getAddress().isEmpty()) {
                rejectionReason = "Server address is required";
            } else if (request.getPort() <= 0) {
                rejectionReason = "Invalid server port";
            } else {
                rejectionReason = "Server validation failed";
            }
        }
        
        // Get the server identifier
        String serverIdentifier = request.getTempId();
        
        // Check if server already has a permanent ID (not starting with "temp-")
        boolean isTemporaryId = serverIdentifier.startsWith("temp-");
        String assignedServerId;
        
        if (isTemporaryId) {
            // Generate new permanent ID for temporary servers
            assignedServerId = generateProperServerId(request.getServerType());
            logger.info("Assigning permanent ID: {} -> {}", serverIdentifier, assignedServerId);
        } else {
            // Server already has permanent ID, keep it
            assignedServerId = serverIdentifier;
            logger.info("Server already has permanent ID: {}", assignedServerId);
            
            // Check if we already know this server
            if (backendServers.containsKey(assignedServerId)) {
                logger.info("Server {} re-registering with new proxy", assignedServerId);
            }
        }
        
        // Send response
        ServerRegistrationResponse response = new ServerRegistrationResponse();
        response.setTempId(serverIdentifier);
        response.setAssignedServerId(assignedServerId);
        response.setSuccess(approved);
        response.setMessage(rejectionReason);
        response.setProxyId(proxyId);
        response.setServerType(request.getServerType());
        response.setAddress(request.getAddress());
        response.setPort(request.getPort());
        
        // Send response to both channels for compatibility
        messageBus.broadcast("server:registration:response", response);
        messageBus.broadcast("server:" + serverIdentifier, response);
        
        logger.info("Registration {}: {} -> {}",
                   approved ? "APPROVED" : "REJECTED",
                   serverIdentifier,
                   approved ? assignedServerId : rejectionReason);
        
        if (approved) {
            // Store server info
            ServerIdentifier serverInfo = new BackendServerIdentifier(
                assignedServerId,
                request.getServerType(),
                request.getFamily(),
                request.getAddress(),
                request.getPort(),
                request.getMaxCapacity()
            );
            backendServers.put(assignedServerId, serverInfo);
            serverHeartbeats.put(assignedServerId, System.currentTimeMillis());
            
            // Remove old temporary entry if this was a new assignment
            if (isTemporaryId && !serverIdentifier.equals(assignedServerId)) {
                backendServers.remove(serverIdentifier);
                serverHeartbeats.remove(serverIdentifier);
            }
            
            // Add server to Velocity's server list so players can connect
            addServerToVelocity(assignedServerId, request.getAddress(), request.getPort());
            
            logger.info("Approved backend server registration: {} - Type: {}",
                       assignedServerId, request.getServerType());
        } else {
            logger.warn("Rejected backend server registration: {} - Reason: {}",
                       request.getTempId(), rejectionReason);
        }
    }
    
    /**
     * Add backend server to Velocity's server list
     */
    private void addServerToVelocity(String serverId, String address, int port) {
        try {
            InetSocketAddress serverAddress = new InetSocketAddress(address, port);
            ServerInfo serverInfo = new ServerInfo(serverId, serverAddress);
            proxy.registerServer(serverInfo);
            logger.info("Added server to Velocity: {} at {}:{}", serverId, address, port);
        } catch (Exception e) {
            logger.error("Failed to add server to Velocity: {} at {}:{}", serverId, address, port, e);
        }
    }
    
    private boolean validateServer(ServerRegistrationRequest request) {
        // Basic validation
        if (request.getTempId() == null) {
            return false;
        }
        
        if (request.getServerType() == null || request.getServerType().isEmpty()) {
            return false;
        }
        
        if (request.getMaxCapacity() <= 0) {
            return false;
        }
        
        // Validate address and port are provided
        if (request.getAddress() == null || request.getAddress().isEmpty()) {
            return false;
        }
        
        if (request.getPort() <= 0 || request.getPort() > 65535) {
            return false;
        }
        
        // Additional validation can be added here:
        // - Check against whitelist/blacklist
        // - Verify server type is supported
        // - Check total network capacity limits
        
        return true;
    }
    
    private void updateServerCapacity(String serverId, int currentCapacity) {
        ServerIdentifier server = backendServers.get(serverId);
        if (server instanceof BackendServerIdentifier) {
            ((BackendServerIdentifier) server).setCurrentCapacity(currentCapacity);
        }
    }
    
    private void startHeartbeat() {
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
                
                // Send proxy heartbeat
                messageBus.broadcast("proxy:heartbeat", currentProxyData);
                
                // Log capacity warnings
                if (config.isAtHardCapacity(currentPlayers)) {
                    logger.warn("Proxy at HARD capacity: {}/{}", currentPlayers, config.getHardCap());
                } else if (config.isAtSoftCapacity(currentPlayers)) {
                    logger.info("Proxy at soft capacity: {}/{}", currentPlayers, config.getSoftCap());
                }
                
                // Update Redis with current info
                if (messageBus instanceof VelocityRedisMessageBus) {
                    VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
                    var commands = redisMessageBus.getRedisConnection().sync();
                    
                    String proxyKey = PROXY_INFO_PREFIX + proxyId;
                    commands.hset(proxyKey, "currentPlayerCount", String.valueOf(currentPlayers));
                    commands.hset(proxyKey, "lastHeartbeat", String.valueOf(System.currentTimeMillis()));
                    commands.expire(proxyKey, config.getTimeoutSeconds());
                }
                
                logger.debug("Sent proxy heartbeat - Current players: {}", currentPlayers);
            } catch (Exception e) {
                logger.error("Error in heartbeat task", e);
            }
        }, 0, config.getHeartbeatInterval(), TimeUnit.SECONDS);
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
                            
                            // Notify network of server removal
                            messageBus.broadcast("server:removed", serverId);
                        }
                        return true;
                    }
                    return false;
                });
                
                // Clean up stale proxies from Redis (in multi-proxy setup)
                if (messageBus instanceof VelocityRedisMessageBus) {
                    VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
                    var commands = redisMessageBus.getRedisConnection().sync();
                    
                    Set<String> proxyIds = commands.smembers(PROXIES_KEY);
                    for (String otherProxyId : proxyIds) {
                        if (!otherProxyId.equals(proxyId)) {
                            String proxyKey = PROXY_INFO_PREFIX + otherProxyId;
                            if (commands.exists(proxyKey) == 0) {
                                commands.srem(PROXIES_KEY, otherProxyId);
                                proxyRegistry.remove(otherProxyId);
                                logger.info("Removed stale proxy from registry: {}", otherProxyId);
                            }
                        }
                    }
                }
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
        
        // Remove from Redis
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            commands.srem(PROXIES_KEY, proxyId);
            commands.del(PROXY_INFO_PREFIX + proxyId);
            logger.info("Removed proxy from Redis registry");
        }
        
        // Notify servers of shutdown
        String address = proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort();
        ProxyAnnouncementMessage shutdown = new ProxyAnnouncementMessage(
            proxyId,
            address,
            proxy.getConfiguration().getShowMaxPlayers(),
            -1 // Indicates shutdown
        );
        messageBus.broadcast("proxy:shutdown", shutdown);
        
        logger.info("VelocityServerLifecycleFeature shutdown complete");
    }
    
    /**
     * Send a request for all backend servers to register
     * This is called when a new proxy starts up
     */
    private void sendRegistrationRequest() {
        logger.info("Sending request for backend servers to register");
        messageBus.broadcast("proxy:request-registrations",
            new ProxyAnnouncementMessage(proxyId,
                proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort(),
                proxy.getConfiguration().getShowMaxPlayers(),
                proxy.getPlayerCount()));
    }
    
    /**
     * Get registered backend servers
     */
    public Map<String, ServerIdentifier> getBackendServers() {
        return new ConcurrentHashMap<>(backendServers);
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
    
    // Server ID generation counters
    private final Map<String, AtomicInteger> serverTypeCounters = new ConcurrentHashMap<>();
    
    /**
     * Generate a proper server ID in the format: <servertype><number>
     * e.g., mini1, mini2, mega3, lobby1
     */
    private String generateProperServerId(String serverType) {
        String type = serverType.toLowerCase();
        AtomicInteger counter = serverTypeCounters.computeIfAbsent(type, k -> new AtomicInteger(0));
        int number = counter.incrementAndGet();
        return type + number;
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