package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.lifecycle.ProxyRegistry;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityRedisMessageBus;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocitySimpleMessageBus;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Velocity implementation of server lifecycle management.
 * Acts as the network leader, handling proxy registration and backend server approval.
 */
public class VelocityServerLifecycleFeature implements VelocityFeature, ProxyRegistry {
    
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
        
        // The MessageBus feature already generates the proxy ID with correct format
        // We should ideally get it from there, but for now we'll generate our own matching ID
        // This matches the format: fulcrum-velocity-{index}
        this.proxyId = "fulcrum-velocity-0";
        
        // Register proxy 
        registerSelfInRedis();
        
        // Setup message handlers
        setupMessageHandlers();
        
        // Start heartbeat and cleanup tasks
        startHeartbeat();
        startCleanupTask();
        
        logger.info("VelocityServerLifecycleFeature initialized - Proxy ID: {}", proxyId);
    }
    
    private void registerSelfInRedis() {
        // Create our proxy announcement
        String address = proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort();
        currentProxyData = new ProxyAnnouncementMessage(
            proxyId,
            address,
            proxy.getConfiguration().getShowMaxPlayers(),
            proxy.getPlayerCount(),
            ProxyAnnouncementMessage.ProxyType.MIXED
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
            proxyData.put("capacity", String.valueOf(proxy.getConfiguration().getShowMaxPlayers()));
            proxyData.put("currentLoad", String.valueOf(proxy.getPlayerCount()));
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
            Object payload = envelope.getPayload();
            if (payload instanceof ServerRegistrationRequest) {
                handleServerRegistration((ServerRegistrationRequest) payload);
            }
        });
        
        // Handle server heartbeats
        messageBus.subscribe("server:heartbeat", envelope -> {
            Object payload = envelope.getPayload();
            if (payload instanceof ServerHeartbeatMessage) {
                ServerHeartbeatMessage heartbeat = (ServerHeartbeatMessage) payload;
                String serverId = heartbeat.getServerId();
                serverHeartbeats.put(serverId, System.currentTimeMillis());
                updateServerCapacity(serverId, heartbeat.getPlayerCount());
            }
        });
        
        // Handle server announcements (post-approval)
        messageBus.subscribe("server:announce", envelope -> {
            Object payload = envelope.getPayload();
            if (payload instanceof ServerAnnouncementMessage) {
                ServerAnnouncementMessage announcement = (ServerAnnouncementMessage) payload;
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
            }
        });
        
        // Handle proxy discovery requests
        messageBus.subscribe("proxy:discovery", envelope -> {
            Object payload = envelope.getPayload();
            if (payload instanceof ProxyDiscoveryRequest) {
                ProxyDiscoveryRequest request = (ProxyDiscoveryRequest) payload;
                
                // Create response with our proxy info
                ProxyDiscoveryResponse response = new ProxyDiscoveryResponse(proxyId);
                ProxyDiscoveryResponse.ProxyInfo proxyInfo = new ProxyDiscoveryResponse.ProxyInfo(
                    proxyId,
                    proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort(),
                    proxy.getConfiguration().getShowMaxPlayers(),
                    proxy.getPlayerCount(),
                    ProxyAnnouncementMessage.ProxyType.MIXED
                );
                response.addProxy(proxyInfo);
                
                messageBus.broadcast("proxy:discovery:response", response);
            }
        });
    }
    
    private void handleServerRegistration(ServerRegistrationRequest request) {
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
            } else {
                rejectionReason = "Server validation failed";
            }
        }
        
        // Create a proper assigned server ID
        String assignedServerId = request.getServerType().toLowerCase() + "-" + request.getTempId().substring(0, 8);
        
        // Send response  
        ServerRegistrationResponse response = new ServerRegistrationResponse();
        response.setTempId(request.getTempId());
        response.setAssignedServerId(assignedServerId);
        response.setSuccess(approved);
        response.setMessage(rejectionReason);
        response.setProxyId(proxyId);
        response.setServerType(request.getServerType());
        response.setAddress(request.getAddress());
        response.setPort(request.getPort());
        
        messageBus.broadcast("server:register:response:" + request.getTempId(), response);
        
        if (approved) {
            // Store initial server info
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
            
            logger.info("Approved backend server registration: {} - Type: {}",
                       assignedServerId, request.getServerType());
        } else {
            logger.warn("Rejected backend server registration: {} - Reason: {}",
                       request.getTempId(), rejectionReason);
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
                
                // Update our proxy data
                String address = proxy.getBoundAddress().getHostString() + ":" + proxy.getBoundAddress().getPort();
                currentProxyData = new ProxyAnnouncementMessage(
                    proxyId,
                    address,
                    proxy.getConfiguration().getShowMaxPlayers(),
                    currentPlayers,
                    ProxyAnnouncementMessage.ProxyType.MIXED
                );
                
                // Send proxy heartbeat
                messageBus.broadcast("proxy:heartbeat", currentProxyData);
                
                // Update Redis with current info
                if (messageBus instanceof VelocityRedisMessageBus) {
                    VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
                    var commands = redisMessageBus.getRedisConnection().sync();
                    
                    String proxyKey = PROXY_INFO_PREFIX + proxyId;
                    commands.hset(proxyKey, "currentLoad", String.valueOf(currentPlayers));
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
            -1, // Indicates shutdown
            ProxyAnnouncementMessage.ProxyType.MIXED
        );
        messageBus.broadcast("proxy:shutdown", shutdown);
        
        logger.info("VelocityServerLifecycleFeature shutdown complete");
    }
    
    // ProxyRegistry implementation
    @Override
    public void registerProxy(String proxyId, ProxyAnnouncementMessage announcement) {
        proxyRegistry.put(proxyId, announcement);
        
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            commands.sadd(PROXIES_KEY, proxyId);
            String proxyKey = PROXY_INFO_PREFIX + proxyId;
            Map<String, String> proxyData = new HashMap<>();
            proxyData.put("address", announcement.getAddress());
            proxyData.put("capacity", String.valueOf(announcement.getCapacity()));
            proxyData.put("currentLoad", String.valueOf(announcement.getCurrentLoad()));
            proxyData.put("type", announcement.getProxyType().toString());
            commands.hset(proxyKey, proxyData);
            commands.expire(proxyKey, config.getTimeoutSeconds());
        }
    }
    
    @Override
    public void unregisterProxy(String proxyId) {
        proxyRegistry.remove(proxyId);
        
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            commands.srem(PROXIES_KEY, proxyId);
            commands.del(PROXY_INFO_PREFIX + proxyId);
        }
    }
    
    @Override
    public Set<String> getRegisteredProxies() {
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            return commands.smembers(PROXIES_KEY);
        }
        // Fallback to local registry
        return proxyRegistry.keySet();
    }
    
    @Override
    public ProxyAnnouncementMessage getProxyData(String proxyId) {
        return proxyRegistry.get(proxyId);
    }
    
    @Override
    public void refreshProxyTTL(String proxyId, ProxyAnnouncementMessage announcement) {
        proxyRegistry.put(proxyId, announcement);
        
        if (messageBus instanceof VelocityRedisMessageBus) {
            VelocityRedisMessageBus redisMessageBus = (VelocityRedisMessageBus) messageBus;
            var commands = redisMessageBus.getRedisConnection().sync();
            
            String proxyKey = PROXY_INFO_PREFIX + proxyId;
            commands.hset(proxyKey, "currentLoad", String.valueOf(announcement.getCurrentLoad()));
            commands.hset(proxyKey, "lastHeartbeat", String.valueOf(System.currentTimeMillis()));
            commands.expire(proxyKey, config.getTimeoutSeconds());
        }
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