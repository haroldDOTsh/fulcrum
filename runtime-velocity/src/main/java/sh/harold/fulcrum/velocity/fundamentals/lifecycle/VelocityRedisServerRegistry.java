package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;
import sh.harold.fulcrum.velocity.config.RedisConfig;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Redis-based implementation of ServerRegistry for production.
 */
public class VelocityRedisServerRegistry implements ServerRegistry {
    
    private static final String SERVER_KEY_PREFIX = "fulcrum:servers:";
    private static final String SERVER_SET_KEY = "fulcrum:server_ids";
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper;
    private final Logger logger;
    
    public VelocityRedisServerRegistry(RedisConfig config, Logger logger) {
        this.logger = logger;
        this.objectMapper = new ObjectMapper();
        
        String redisUri = String.format("redis://%s%s@%s:%d/%d",
            config.getPassword().isEmpty() ? "" : ":" + config.getPassword(),
            config.getPassword().isEmpty() ? "" : "@",
            config.getHost(),
            config.getPort(),
            config.getDatabase()
        );
        
        this.redisClient = RedisClient.create(redisUri);
        this.connection = redisClient.connect();
        
        logger.info("Connected to Redis for server registry");
    }
    
    @Override
    public CompletableFuture<RegistrationResult> register(ServerRegistration registration, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String serverId = generateServerId(registration, sync);
                
                // Check if already exists
                String existingData = sync.get(SERVER_KEY_PREFIX + serverId);
                if (existingData != null) {
                    ServerMetadata existing = deserializeMetadata(existingData);
                    if (existing.instanceUuid().equals(instanceUuid)) {
                        // Same instance reclaiming
                        ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
                        saveMetadata(serverId, metadata, sync);
                        return RegistrationResult.reclaimed(serverId, metadata);
                    } else if (existing.isCrashed(60)) {
                        // Server crashed, allow takeover
                        ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
                        saveMetadata(serverId, metadata, sync);
                        return RegistrationResult.reclaimed(serverId, metadata);
                    } else {
                        return RegistrationResult.failure("Server ID already in use: " + serverId);
                    }
                }
                
                // New registration
                ServerMetadata metadata = createMetadata(serverId, registration, instanceUuid);
                saveMetadata(serverId, metadata, sync);
                sync.sadd(SERVER_SET_KEY, serverId);
                
                return RegistrationResult.success(serverId, metadata);
            } catch (Exception e) {
                logger.error("Failed to register server", e);
                return RegistrationResult.failure("Registration failed: " + e.getMessage());
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, ServerStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String data = sync.get(SERVER_KEY_PREFIX + serverId);
                if (data != null) {
                    ServerMetadata metadata = deserializeMetadata(data);
                    ServerMetadata updated = metadata.withStatus(status);
                    saveMetadata(serverId, updated, sync);
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.error("Failed to update status", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> heartbeat(ServerHeartbeat heartbeat) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String data = sync.get(SERVER_KEY_PREFIX + heartbeat.serverId());
                if (data != null) {
                    ServerMetadata metadata = deserializeMetadata(data);
                    ServerMetadata updated = metadata.withHeartbeat(heartbeat.timestamp());
                    saveMetadata(heartbeat.serverId(), updated, sync);
                    return true;
                }
                return false;
            } catch (Exception e) {
                logger.error("Failed to update heartbeat", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                String data = sync.get(SERVER_KEY_PREFIX + serverId);
                if (data != null) {
                    return Optional.of(deserializeMetadata(data));
                }
                return Optional.empty();
            } catch (Exception e) {
                logger.error("Failed to get server", e);
                return Optional.empty();
            }
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByFamily(String family) {
        return getAllServers().thenApply(servers ->
            servers.stream()
                .filter(s -> s.family().equals(family))
                .collect(Collectors.toList())
        );
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByType(ServerType type) {
        return getAllServers().thenApply(servers ->
            servers.stream()
                .filter(s -> s.type() == type)
                .collect(Collectors.toList())
        );
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByStatus(ServerStatus status) {
        return getAllServers().thenApply(servers ->
            servers.stream()
                .filter(s -> s.status() == status)
                .collect(Collectors.toList())
        );
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getAllServers() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                Set<String> serverIds = sync.smembers(SERVER_SET_KEY);
                
                List<ServerMetadata> servers = new ArrayList<>();
                for (String serverId : serverIds) {
                    String data = sync.get(SERVER_KEY_PREFIX + serverId);
                    if (data != null) {
                        servers.add(deserializeMetadata(data));
                    }
                }
                
                return servers;
            } catch (Exception e) {
                logger.error("Failed to get all servers", e);
                return Collections.emptyList();
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> unregister(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCommands<String, String> sync = connection.sync();
                sync.del(SERVER_KEY_PREFIX + serverId);
                sync.srem(SERVER_SET_KEY, serverId);
                return true;
            } catch (Exception e) {
                logger.error("Failed to unregister server", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Collection<String>> checkCrashedServers(int timeoutSeconds) {
        return getAllServers().thenApply(servers -> {
            List<String> crashed = new ArrayList<>();
            for (ServerMetadata server : servers) {
                if (server.isCrashed(timeoutSeconds)) {
                    crashed.add(server.id());
                    updateStatus(server.id(), ServerStatus.OFFLINE);
                }
            }
            return crashed;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> isReserved(String serverId, UUID instanceUuid) {
        return getServer(serverId).thenApply(opt -> {
            if (opt.isPresent()) {
                ServerMetadata metadata = opt.get();
                return metadata.instanceUuid().equals(instanceUuid) ||
                       metadata.status() == ServerStatus.OFFLINE ||
                       metadata.isCrashed(60);
            }
            return false;
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getBestServer(String family) {
        return getServersByFamily(family).thenApply(servers ->
            servers.stream()
                .filter(s -> s.status() == ServerStatus.READY)
                .filter(s -> !s.isCrashed(30))
                .findFirst()
        );
    }
    
    private String generateServerId(ServerRegistration registration, RedisCommands<String, String> sync) {
        if (registration.type() == ServerType.PROXY) {
            return generateProxyId(sync);
        } else {
            return registration.family() + "-" + generateGameServerId(registration.family(), sync);
        }
    }
    
    private String generateProxyId(RedisCommands<String, String> sync) {
        String prefix = "fulcrum-proxy-";
        Set<String> serverIds = sync.smembers(SERVER_SET_KEY);
        Set<Integer> usedIds = new HashSet<>();
        
        for (String serverId : serverIds) {
            if (serverId.startsWith(prefix)) {
                try {
                    String numStr = serverId.substring(prefix.length());
                    usedIds.add(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    // Ignore malformed IDs
                }
            }
        }
        
        int id = 0;
        while (usedIds.contains(id)) {
            id++;
        }
        
        return prefix + id;
    }
    
    private String generateGameServerId(String family, RedisCommands<String, String> sync) {
        String prefix = family + "-";
        Set<String> serverIds = sync.smembers(SERVER_SET_KEY);
        Set<Integer> usedIds = new HashSet<>();
        
        for (String serverId : serverIds) {
            if (serverId.startsWith(prefix)) {
                try {
                    String numStr = serverId.substring(prefix.length());
                    usedIds.add(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    // Ignore malformed IDs
                }
            }
        }
        
        int id = 0;
        while (usedIds.contains(id)) {
            id++;
        }
        
        return String.valueOf(id);
    }
    
    private ServerMetadata createMetadata(String serverId, ServerRegistration registration, UUID instanceUuid) {
        return new ServerMetadata(
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
    }
    
    private void saveMetadata(String serverId, ServerMetadata metadata, RedisCommands<String, String> sync) {
        try {
            String json = objectMapper.writeValueAsString(metadata);
            sync.setex(SERVER_KEY_PREFIX + serverId, 120, json); // 2 minute TTL
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }
    
    private ServerMetadata deserializeMetadata(String json) {
        try {
            return objectMapper.readValue(json, ServerMetadata.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize metadata", e);
        }
    }
    
    public void shutdown() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
}