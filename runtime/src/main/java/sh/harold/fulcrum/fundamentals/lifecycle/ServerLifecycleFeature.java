package sh.harold.fulcrum.fundamentals.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.event.ServerReadyEvent;
import sh.harold.fulcrum.api.lifecycle.event.ServerShutdownEvent;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.redis.JedisRedisOperations;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class ServerLifecycleFeature implements PluginFeature {
    
    private JavaPlugin plugin;
    private DependencyContainer container;
    private ServerRegistry serverRegistry;
    private ServerIdentifier serverIdentifier;
    private ServerLifecycleBootstrap bootstrap;
    private ScheduledExecutorService scheduler;
    private String serverId;
    private UUID instanceUuid;
    private ServerMetadata currentMetadata;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        
        // Get message bus dependency
        MessageBus messageBus = container.get(MessageBus.class);
        
        // Try to create Redis-backed registry if Redis is enabled
        boolean redisEnabled = plugin.getConfig().getBoolean("redis.enabled", false);
        
        if (redisEnabled) {
            ConfigurationSection redisSection = plugin.getConfig().getConfigurationSection("redis");
            if (redisSection != null) {
                try {
                    RedisConfig redisConfig = RedisConfig.builder()
                        .host(redisSection.getString("host", "localhost"))
                        .port(redisSection.getInt("port", 6379))
                        .database(redisSection.getInt("database", 0))
                        .password(redisSection.getString("password"))
                        .maxConnections(redisSection.getInt("pool.max-connections", 20))
                        .maxIdleConnections(redisSection.getInt("pool.max-idle", 10))
                        .minIdleConnections(redisSection.getInt("pool.min-idle", 5))
                        .build();
                    
                    this.serverRegistry = new RedisServerRegistry(redisConfig, messageBus);
                    plugin.getLogger().info("Using Redis-backed ServerRegistry");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to create Redis registry, falling back to in-memory", e);
                    this.serverRegistry = new DefaultServerRegistry(null, messageBus);
                }
            } else {
                plugin.getLogger().warning("Redis enabled but configuration section not found");
                this.serverRegistry = new DefaultServerRegistry(null, messageBus);
            }
        } else {
            // Use in-memory registry when Redis is not enabled
            JedisRedisOperations redis = container.get(JedisRedisOperations.class);
            this.serverRegistry = new DefaultServerRegistry(redis, messageBus);
            plugin.getLogger().info("Using in-memory ServerRegistry");
        }
        
        // Create bootstrap
        this.bootstrap = new ServerLifecycleBootstrapImpl(plugin, serverRegistry);
        
        // Generate instance UUID for this server session
        this.instanceUuid = UUID.randomUUID();
        
        // Create registration from config
        var registration = ((ServerLifecycleBootstrapImpl)bootstrap).createRegistrationFromConfig();
        
        // Register the server during initialization
        RegistrationResult result = bootstrap.bootstrapRegister(registration, instanceUuid).join();
        if (result.success()) {
            this.serverId = result.serverId();
            this.currentMetadata = result.metadata();
            
            // Create server identifier
            this.serverIdentifier = new DefaultServerIdentifier(
                serverId,
                registration.family(),
                registration.type(),
                ServerStatus.STARTING,
                instanceUuid,
                registration.address(),
                registration.port(),
                registration.softCap(),
                registration.hardCap()
            );
            
            plugin.getLogger().info("Server registered with ID: " + serverId);
            
            // Register services
            container.register(ServerRegistry.class, serverRegistry);
            container.register(ServerIdentifier.class, serverIdentifier);
            container.register(ServerLifecycleBootstrap.class, bootstrap);
            
            // Start heartbeat
            startHeartbeat();
            
            // Mark server as ready after it's fully started
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                bootstrap.markReady(serverId);
                if (messageBus != null) {
                    ServerMetadata metadata = serverRegistry.getServer(serverId).join().orElse(null);
                    if (metadata != null) {
                        // Publish ready event - disabled until MessageBus interface is clarified
                        // messageBus.publish(new ServerReadyEvent(metadata));
                    }
                }
            }, 20L); // 1 second delay
        } else {
            plugin.getLogger().severe("Failed to register server: " + result.message());
        }
    }
    
    private void startHeartbeat() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("ServerLifecycle-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // Calculate TPS (simplified - in real implementation you'd track actual TPS)
                double tps = 20.0; // TODO: Get actual TPS from server
                
                ServerHeartbeat heartbeat = ServerHeartbeat.create(
                    serverId,
                    Bukkit.getOnlinePlayers().size(),
                    tps,
                    Bukkit.getMaxPlayers(),
                    Bukkit.getMaxPlayers() + 10
                );
                
                serverRegistry.heartbeat(heartbeat);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send heartbeat", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    @Override
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        
        if (serverId != null && serverRegistry != null && bootstrap != null) {
            try {
                // Begin shutdown process
                bootstrap.beginShutdown(serverId);
                
                // Notify shutdown - disabled until MessageBus interface is clarified
                // MessageBus messageBus = container.get(MessageBus.class);
                // if (messageBus != null && currentMetadata != null) {
                //     messageBus.publish(new ServerShutdownEvent(
                //         currentMetadata,
                //         "Server shutting down",
                //         null,
                //         false
                //     ));
                // }
                
                // Complete shutdown
                bootstrap.completeShutdown(serverId);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to update server status on shutdown", e);
            }
        }
    }
    
    @Override
    public Class<?>[] getDependencies() {
        // Optional dependencies - will work without them
        return new Class<?>[] {};
    }
    
    public String getName() {
        return "ServerLifecycle";
    }
    
    @Override
    public int getPriority() {
        // Must initialize before MessageBus (which has priority 60)
        return 5;
    }
}