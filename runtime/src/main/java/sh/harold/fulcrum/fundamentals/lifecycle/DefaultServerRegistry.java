package sh.harold.fulcrum.fundamentals.lifecycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.event.ServerCrashedEvent;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;
import sh.harold.fulcrum.api.lifecycle.util.ServerIdGenerator;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.runtime.redis.JedisRedisOperations;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default implementation of ServerRegistry.
 * Currently uses in-memory storage due to Redis operations being temporarily disabled.
 * Will be updated to use Redis once message-bus integration is complete.
 */
public class DefaultServerRegistry implements ServerRegistry {
    
    // In-memory storage (temporary until Redis is re-enabled)
    private final Map<String, ServerMetadata> servers = new ConcurrentHashMap<>();
    private final Map<String, ServerHeartbeat> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, UUID> reservedIds = new ConcurrentHashMap<>();
    
    private final JedisRedisOperations redis;
    private final MessageBus messageBus;
    private final Gson gson;
    
    public DefaultServerRegistry(JedisRedisOperations redis, MessageBus messageBus) {
        this.redis = redis;
        this.messageBus = messageBus;
        this.gson = new GsonBuilder().create();
    }
    
    @Override
    public CompletableFuture<RegistrationResult> register(ServerRegistration registration, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate server ID based on family
                Set<String> existingIds = servers.keySet();
                boolean isDynamic = registration.type() == ServerType.GAME && registration.family().equals("dynamic");
                String serverId = ServerIdGenerator.generateId(registration.family(), existingIds, isDynamic);
                
                // Create server metadata
                ServerMetadata metadata = new ServerMetadata(
                    serverId,
                    registration.family(),
                    registration.type(),
                    registration.address(),
                    registration.port(),
                    ServerStatus.STARTING,
                    Instant.now(),
                    Instant.now(),
                    instanceUuid
                );
                
                // Store server metadata in memory
                servers.put(serverId, metadata);
                
                return RegistrationResult.success(serverId, metadata);
            } catch (Exception e) {
                return RegistrationResult.failure("Failed to register server: " + e.getMessage());
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, ServerStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServerMetadata current = servers.get(serverId);
                if (current == null) {
                    return false;
                }
                
                ServerMetadata updated = current.withStatus(status);
                servers.put(serverId, updated);
                
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> heartbeat(ServerHeartbeat heartbeat) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Update heartbeat data
                heartbeats.put(heartbeat.serverId(), heartbeat);
                
                // Update server's last heartbeat time
                ServerMetadata current = servers.get(heartbeat.serverId());
                if (current != null) {
                    ServerMetadata updated = current.withHeartbeat(heartbeat.timestamp());
                    servers.put(heartbeat.serverId(), updated);
                }
                
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            return Optional.ofNullable(servers.get(serverId));
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByFamily(String family) {
        return CompletableFuture.supplyAsync(() -> {
            return servers.values().stream()
                .filter(s -> family.equals(s.family()))
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByType(ServerType type) {
        return CompletableFuture.supplyAsync(() -> {
            return servers.values().stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByStatus(ServerStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            return servers.values().stream()
                .filter(s -> s.status() == status)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getAllServers() {
        return CompletableFuture.supplyAsync(() -> {
            return new ArrayList<>(servers.values());
        });
    }
    
    @Override
    public CompletableFuture<Boolean> unregister(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                servers.remove(serverId);
                heartbeats.remove(serverId);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Collection<String>> checkCrashedServers(int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> crashedServers = new ArrayList<>();
            
            servers.values().forEach(server -> {
                if (server.status() == ServerStatus.READY && server.isCrashed(timeoutSeconds)) {
                    crashedServers.add(server.id());
                    handleCrashedServer(server);
                }
            });
            
            return crashedServers;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> isReserved(String serverId, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            // Check if server ID is in reserved set
            UUID reserved = reservedIds.get(serverId);
            if (reserved == null) {
                return false;
            }
            
            // Check if the instance UUID matches (for reclaiming after crash)
            return reserved.equals(instanceUuid);
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getBestServer(String family) {
        return getServersByFamily(family).thenApply(servers -> {
            return servers.stream()
                .filter(s -> s.status() == ServerStatus.READY)
                .min((s1, s2) -> {
                    // Simple comparison based on last heartbeat (fresher is better)
                    return s2.lastHeartbeat().compareTo(s1.lastHeartbeat());
                });
        });
    }
    
    private void handleCrashedServer(ServerMetadata server) {
        // Update status to offline
        updateStatus(server.id(), ServerStatus.OFFLINE);
        
        // Add to reserved IDs for grace period
        reservedIds.put(server.id(), server.instanceUuid());
        
        // Publish crash event - disabled until MessageBus interface is clarified
        // if (messageBus != null) {
        //     messageBus.publish(new ServerCrashedEvent(server, Duration.between(server.lastHeartbeat(), Instant.now())));
        // }
    }
    
    /**
     * Helper method to migrate to Redis when it becomes available.
     * This will be called once message-bus integration is complete.
     */
    public void migrateToRedis() {
        // TODO: Implement migration of in-memory data to Redis
        // This will be done when Redis operations are re-enabled
    }
}