package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.lifecycle.ServerHeartbeat;
import sh.harold.fulcrum.api.lifecycle.ServerLifecycleBootstrap;
import sh.harold.fulcrum.api.lifecycle.ServerMetadata;
import sh.harold.fulcrum.api.lifecycle.ServerRegistry;
import sh.harold.fulcrum.api.lifecycle.ServerStatus;
import sh.harold.fulcrum.api.lifecycle.ServerType;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VelocityServerLifecycleFeature implements VelocityFeature {
    
    private final ProxyServer proxy;
    private Logger logger;
    private final FulcrumVelocityPlugin plugin;
    private final ConfigLoader configLoader;
    
    private ServiceLocator serviceLocator;
    private ServerLifecycleBootstrap bootstrap;
    private ServerRegistry registry;
    private String serverId;
    private UUID instanceUuid;
    private ScheduledTask heartbeatTask;
    
    @Inject
    public VelocityServerLifecycleFeature(ProxyServer proxy, 
                                         FulcrumVelocityPlugin plugin, ConfigLoader configLoader) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.instanceUuid = UUID.randomUUID();
    }
    
    @Override
    public String getName() {
        return "ServerLifecycle";
    }
    
    @Override
    public int getPriority() {
        return 5; // Load before MessageBus (priority 10)
    }
    
    @Override
    public boolean isFundamental() {
        return true; // This is a fundamental feature
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.serviceLocator = serviceLocator;
        this.logger = logger;
        
        ServerLifecycleConfig config = configLoader.getServerLifecycleConfig();
        if (config == null || !config.isEnabled()) {
            logger.info("ServerLifecycle feature is disabled");
            return;
        }
        
        logger.info("Initializing ServerLifecycle feature for Velocity proxy");
        
        // Initialize registry based on configuration
        if (configLoader.getRedisConfig() != null && configLoader.getRedisConfig().isEnabled()) {
            registry = new VelocityRedisServerRegistry(configLoader.getRedisConfig(), logger);
            logger.info("Using Redis-based server registry");
        } else {
            registry = new VelocityDefaultServerRegistry();
            logger.info("Using in-memory server registry");
        }
        
        // Initialize bootstrap
        bootstrap = new VelocityServerLifecycleBootstrap(registry, instanceUuid);
        
        // Register to service locator
        serviceLocator.register(ServerLifecycleBootstrap.class, bootstrap);
        serviceLocator.register(ServerRegistry.class, registry);
        
        // Register the proxy
        registerProxy();
        
        // Start heartbeat
        startHeartbeat();
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down ServerLifecycle feature for Velocity proxy");
        
        // Stop heartbeat
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        
        // Begin shutdown process
        if (bootstrap != null && serverId != null) {
            try {
                bootstrap.beginShutdown(serverId).thenCompose(result -> {
                    if (result) {
                        return bootstrap.completeShutdown(serverId);
                    }
                    return null;
                }).join();
                logger.info("Proxy {} shutdown completed", serverId);
            } catch (Exception e) {
                logger.error("Failed to complete shutdown process", e);
            }
        }
    }
    
    @Override
    public boolean isEnabled() {
        ServerLifecycleConfig config = configLoader.getServerLifecycleConfig();
        return config != null && config.isEnabled();
    }
    
    private void registerProxy() {
        try {
            // Generate proxy ID automatically
            serverId = generateProxyId();
            
            // Get proxy bind address
            InetSocketAddress bindAddress = proxy.getBoundAddress();
            String address = bindAddress.getAddress().getHostAddress();
            int port = bindAddress.getPort();
            
            // Create registration
            ServerRegistration registration = ServerRegistration.proxy(
                "fulcrum", // family
                address,
                port
            );
            
            // Register with bootstrap
            RegistrationResult result = bootstrap.bootstrapRegister(registration, instanceUuid)
                .thenCompose(regResult -> {
                    if (regResult.success()) {
                        serverId = regResult.serverId();
                        logger.info("Proxy registered with ID: {}", serverId);
                        // Mark as ready
                        return bootstrap.markReady(serverId).thenApply(ready -> regResult);
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(regResult);
                })
                .join();
            
            if (result.success()) {
                logger.info("Proxy successfully registered and marked as ready: {}", serverId);
            } else {
                logger.error("Failed to register proxy: {}", result.message());
            }
            
        } catch (Exception e) {
            logger.error("Error during proxy registration", e);
        }
    }
    
    private String generateProxyId() {
        try {
            // Query existing proxies to find next available contiguous ID
            Collection<ServerMetadata> proxies = registry.getServersByType(ServerType.PROXY).join();
            
            Set<Integer> usedIds = new HashSet<>();
            String prefix = "fulcrum-proxy-";
            
            for (ServerMetadata proxy : proxies) {
                String id = proxy.id();
                if (id.startsWith(prefix)) {
                    try {
                        String numStr = id.substring(prefix.length());
                        usedIds.add(Integer.parseInt(numStr));
                    } catch (NumberFormatException e) {
                        // Ignore malformed IDs
                    }
                }
            }
            
            // Find lowest available contiguous number
            int nextId = 0;
            while (usedIds.contains(nextId)) {
                nextId++;
            }
            
            return prefix + nextId;
        } catch (Exception e) {
            logger.warn("Failed to query existing proxies, using fallback ID generation", e);
            // Fallback to UUID-based ID
            return "fulcrum-proxy-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
    
    private void startHeartbeat() {
        // 30-second heartbeat interval
        long intervalSeconds = 30;
        
        heartbeatTask = proxy.getScheduler()
            .buildTask(plugin, this::sendHeartbeat)
            .repeat(Duration.ofSeconds(intervalSeconds))
            .schedule();
        
        logger.info("Started heartbeat task with interval: {} seconds", intervalSeconds);
    }
    
    private void sendHeartbeat() {
        if (serverId != null && registry != null) {
            try {
                int currentPlayers = proxy.getPlayerCount();
                int maxPlayers = proxy.getConfiguration().getShowMaxPlayers();
                
                // Create heartbeat with correct parameters
                ServerHeartbeat heartbeat = ServerHeartbeat.create(
                    serverId,
                    currentPlayers,
                    20.0, // Proxies don't have TPS, use 20.0
                    maxPlayers, // Use max players as soft cap
                    maxPlayers  // Use max players as hard cap
                );
                
                registry.heartbeat(heartbeat).thenAccept(success -> {
                    if (!success) {
                        logger.warn("Heartbeat failed for proxy {}", serverId);
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to send heartbeat", e);
            }
        }
    }
}