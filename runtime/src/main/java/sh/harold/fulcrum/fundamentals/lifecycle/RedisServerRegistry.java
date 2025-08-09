package sh.harold.fulcrum.fundamentals.lifecycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;
import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.event.ServerCrashedEvent;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;
import sh.harold.fulcrum.api.lifecycle.util.ServerIdGenerator;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Redis-based implementation of ServerRegistry.
 * Provides persistent storage with automatic crash detection via TTL.
 */
public class RedisServerRegistry implements ServerRegistry {
    
    private static final Logger LOGGER = Logger.getLogger(RedisServerRegistry.class.getName());
    
    // Redis key prefixes
    private static final String KEY_PREFIX = "fulcrum:server:";
    private static final String METADATA_PREFIX = KEY_PREFIX + "metadata:";
    private static final String HEARTBEAT_PREFIX = KEY_PREFIX + "heartbeat:";
    private static final String IDS_PREFIX = KEY_PREFIX + "ids:";
    private static final String FAMILIES_PREFIX = KEY_PREFIX + "families:";
    private static final String ALL_SERVERS_KEY = KEY_PREFIX + "all";
    private static final String RESERVED_PREFIX = KEY_PREFIX + "reserved:";
    
    // TTL settings
    private static final int DEFAULT_TTL_SECONDS = 90;
    private static final int RESERVED_TTL_SECONDS = 300; // 5 minutes grace period for crashed servers
    
    private final JedisPool jedisPool;
    private final MessageBus messageBus;
    private final Gson gson;
    private final boolean redisAvailable;
    
    // Fallback in-memory storage when Redis is unavailable
    private final Map<String, ServerMetadata> memoryFallback = new ConcurrentHashMap<>();
    private final Map<String, ServerHeartbeat> memoryHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, UUID> memoryReserved = new ConcurrentHashMap<>();
    
    public RedisServerRegistry(RedisConfig config, MessageBus messageBus) {
        this.messageBus = messageBus;
        this.gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();
        
        JedisPool pool = null;
        boolean available = false;
        
        if (config != null) {
            try {
                pool = createJedisPool(config);
                available = testConnection(pool);
                if (available) {
                    LOGGER.info("Redis connection established for ServerRegistry");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to connect to Redis, using in-memory fallback", e);
            }
        }
        
        this.jedisPool = pool;
        this.redisAvailable = available;
        
        if (!redisAvailable) {
            LOGGER.warning("Redis unavailable - ServerRegistry using in-memory storage");
        }
    }
    
    private JedisPool createJedisPool(RedisConfig config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getMaxConnections());
        poolConfig.setMaxIdle(config.getMaxIdleConnections());
        poolConfig.setMinIdle(config.getMinIdleConnections());
        poolConfig.setMaxWaitMillis(config.getConnectionTimeout().toMillis());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                (int) config.getConnectionTimeout().toMillis(),
                config.getPassword(), config.getDatabase());
        } else {
            return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                (int) config.getConnectionTimeout().toMillis(),
                null, config.getDatabase());
        }
    }
    
    private boolean testConnection(JedisPool pool) {
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public CompletableFuture<RegistrationResult> register(ServerRegistration registration, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Find next available ID
                String serverId = findNextAvailableId(registration.family(), registration.type());
                
                // Create metadata
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
                
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        String json = gson.toJson(metadata);
                        
                        // Store metadata with TTL
                        jedis.setex(METADATA_PREFIX + serverId, DEFAULT_TTL_SECONDS, json);
                        
                        // Add to server sets
                        jedis.sadd(ALL_SERVERS_KEY, serverId);
                        jedis.sadd(FAMILIES_PREFIX + registration.family(), serverId);
                        jedis.sadd(IDS_PREFIX + registration.type().name(), serverId);
                        
                        // Remove from reserved if it was there
                        jedis.del(RESERVED_PREFIX + serverId);
                    }
                } else {
                    memoryFallback.put(serverId, metadata);
                }
                
                return RegistrationResult.success(serverId, metadata);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to register server", e);
                return RegistrationResult.failure("Registration failed: " + e.getMessage());
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, ServerStatus status) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        String json = jedis.get(METADATA_PREFIX + serverId);
                        if (json == null) return false;
                        
                        ServerMetadata metadata = gson.fromJson(json, ServerMetadata.class);
                        ServerMetadata updated = metadata.withStatus(status);
                        
                        jedis.setex(METADATA_PREFIX + serverId, DEFAULT_TTL_SECONDS, gson.toJson(updated));
                        return true;
                    }
                } else {
                    ServerMetadata metadata = memoryFallback.get(serverId);
                    if (metadata != null) {
                        memoryFallback.put(serverId, metadata.withStatus(status));
                        return true;
                    }
                    return false;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to update server status", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> heartbeat(ServerHeartbeat heartbeat) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        // Update heartbeat
                        String heartbeatJson = gson.toJson(heartbeat);
                        jedis.setex(HEARTBEAT_PREFIX + heartbeat.serverId(), DEFAULT_TTL_SECONDS, heartbeatJson);
                        
                        // Update metadata last heartbeat
                        String metadataJson = jedis.get(METADATA_PREFIX + heartbeat.serverId());
                        if (metadataJson != null) {
                            ServerMetadata metadata = gson.fromJson(metadataJson, ServerMetadata.class);
                            ServerMetadata updated = metadata.withHeartbeat(heartbeat.timestamp());
                            jedis.setex(METADATA_PREFIX + heartbeat.serverId(), DEFAULT_TTL_SECONDS, gson.toJson(updated));
                        }
                        
                        // Refresh TTL on all server keys
                        jedis.expire(METADATA_PREFIX + heartbeat.serverId(), DEFAULT_TTL_SECONDS);
                        
                        return true;
                    }
                } else {
                    memoryHeartbeats.put(heartbeat.serverId(), heartbeat);
                    ServerMetadata metadata = memoryFallback.get(heartbeat.serverId());
                    if (metadata != null) {
                        memoryFallback.put(heartbeat.serverId(), metadata.withHeartbeat(heartbeat.timestamp()));
                    }
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to process heartbeat", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getServer(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        String json = jedis.get(METADATA_PREFIX + serverId);
                        if (json != null) {
                            return Optional.of(gson.fromJson(json, ServerMetadata.class));
                        }
                    }
                } else {
                    ServerMetadata metadata = memoryFallback.get(serverId);
                    if (metadata != null) {
                        return Optional.of(metadata);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get server: " + serverId, e);
            }
            return Optional.empty();
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByFamily(String family) {
        return CompletableFuture.supplyAsync(() -> {
            List<ServerMetadata> servers = new ArrayList<>();
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        Set<String> serverIds = jedis.smembers(FAMILIES_PREFIX + family);
                        for (String serverId : serverIds) {
                            String json = jedis.get(METADATA_PREFIX + serverId);
                            if (json != null) {
                                servers.add(gson.fromJson(json, ServerMetadata.class));
                            }
                        }
                    }
                } else {
                    servers = memoryFallback.values().stream()
                        .filter(s -> family.equals(s.family()))
                        .collect(Collectors.toList());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get servers by family", e);
            }
            return servers;
        });
    }
    
    @Override
    public CompletableFuture<Collection<ServerMetadata>> getServersByType(ServerType type) {
        return CompletableFuture.supplyAsync(() -> {
            List<ServerMetadata> servers = new ArrayList<>();
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        Set<String> serverIds = jedis.smembers(IDS_PREFIX + type.name());
                        for (String serverId : serverIds) {
                            String json = jedis.get(METADATA_PREFIX + serverId);
                            if (json != null) {
                                servers.add(gson.fromJson(json, ServerMetadata.class));
                            }
                        }
                    }
                } else {
                    servers = memoryFallback.values().stream()
                        .filter(s -> s.type() == type)
                        .collect(Collectors.toList());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get servers by type", e);
            }
            return servers;
        });
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
            List<ServerMetadata> servers = new ArrayList<>();
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        Set<String> serverIds = jedis.smembers(ALL_SERVERS_KEY);
                        for (String serverId : serverIds) {
                            String json = jedis.get(METADATA_PREFIX + serverId);
                            if (json != null) {
                                servers.add(gson.fromJson(json, ServerMetadata.class));
                            } else {
                                // Clean up stale reference
                                jedis.srem(ALL_SERVERS_KEY, serverId);
                            }
                        }
                    }
                } else {
                    servers = new ArrayList<>(memoryFallback.values());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get all servers", e);
            }
            return servers;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> unregister(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        // Get metadata for cleanup
                        String json = jedis.get(METADATA_PREFIX + serverId);
                        if (json != null) {
                            ServerMetadata metadata = gson.fromJson(json, ServerMetadata.class);
                            
                            // Remove from all sets
                            jedis.srem(ALL_SERVERS_KEY, serverId);
                            jedis.srem(FAMILIES_PREFIX + metadata.family(), serverId);
                            jedis.srem(IDS_PREFIX + metadata.type().name(), serverId);
                        }
                        
                        // Delete keys
                        jedis.del(METADATA_PREFIX + serverId);
                        jedis.del(HEARTBEAT_PREFIX + serverId);
                        
                        return true;
                    }
                } else {
                    memoryFallback.remove(serverId);
                    memoryHeartbeats.remove(serverId);
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to unregister server", e);
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<Collection<String>> checkCrashedServers(int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> crashedServers = new ArrayList<>();
            
            if (redisAvailable) {
                // Redis TTL handles crash detection automatically
                // We just need to clean up the sets
                try (Jedis jedis = jedisPool.getResource()) {
                    Set<String> allServers = jedis.smembers(ALL_SERVERS_KEY);
                    for (String serverId : allServers) {
                        if (!jedis.exists(METADATA_PREFIX + serverId)) {
                            crashedServers.add(serverId);
                            handleCrashedServer(serverId);
                        }
                    }
                }
            } else {
                // Manual crash detection for in-memory fallback
                memoryFallback.values().forEach(server -> {
                    if (server.status() == ServerStatus.READY && server.isCrashed(timeoutSeconds)) {
                        crashedServers.add(server.id());
                        handleCrashedServer(server.id());
                    }
                });
            }
            
            return crashedServers;
        });
    }
    
    @Override
    public CompletableFuture<Boolean> isReserved(String serverId, UUID instanceUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (redisAvailable) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        String reserved = jedis.get(RESERVED_PREFIX + serverId);
                        if (reserved != null) {
                            UUID reservedUuid = UUID.fromString(reserved);
                            return reservedUuid.equals(instanceUuid);
                        }
                    }
                } else {
                    UUID reserved = memoryReserved.get(serverId);
                    return reserved != null && reserved.equals(instanceUuid);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to check reservation", e);
            }
            return false;
        });
    }
    
    @Override
    public CompletableFuture<Optional<ServerMetadata>> getBestServer(String family) {
        return getServersByFamily(family).thenApply(servers -> 
            servers.stream()
                .filter(s -> s.status() == ServerStatus.READY)
                .min((s1, s2) -> {
                    // Prefer servers with more recent heartbeats
                    return s2.lastHeartbeat().compareTo(s1.lastHeartbeat());
                })
        );
    }
    
    private String findNextAvailableId(String family, ServerType type) {
        Set<String> existingIds = new HashSet<>();
        
        if (redisAvailable) {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> serverIds = jedis.smembers(FAMILIES_PREFIX + family);
                existingIds.addAll(serverIds);
            }
        } else {
            existingIds = memoryFallback.values().stream()
                .filter(s -> family.equals(s.family()))
                .map(ServerMetadata::id)
                .collect(Collectors.toSet());
        }
        
        boolean isDynamic = type == ServerType.GAME && family.equals("dynamic");
        return ServerIdGenerator.generateId(family, existingIds, isDynamic);
    }
    
    private void handleCrashedServer(String serverId) {
        try {
            if (redisAvailable) {
                try (Jedis jedis = jedisPool.getResource()) {
                    // Mark as reserved for grace period
                    String metadataJson = jedis.get(METADATA_PREFIX + serverId);
                    if (metadataJson != null) {
                        ServerMetadata metadata = gson.fromJson(metadataJson, ServerMetadata.class);
                        jedis.setex(RESERVED_PREFIX + serverId, RESERVED_TTL_SECONDS, 
                            metadata.instanceUuid().toString());
                    }
                    
                    // Clean up from sets
                    jedis.srem(ALL_SERVERS_KEY, serverId);
                }
            } else {
                ServerMetadata metadata = memoryFallback.get(serverId);
                if (metadata != null) {
                    memoryReserved.put(serverId, metadata.instanceUuid());
                    memoryFallback.remove(serverId);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to handle crashed server", e);
        }
    }
    
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
    
    /**
     * Custom Gson adapter for Instant serialization
     */
    private static class InstantTypeAdapter extends com.google.gson.TypeAdapter<Instant> {
        @Override
        public void write(com.google.gson.stream.JsonWriter out, Instant value) throws java.io.IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }
        
        @Override
        public Instant read(com.google.gson.stream.JsonReader in) throws java.io.IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}