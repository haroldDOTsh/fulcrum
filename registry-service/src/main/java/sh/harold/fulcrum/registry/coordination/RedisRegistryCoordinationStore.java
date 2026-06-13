package sh.harold.fulcrum.registry.coordination;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RedisRegistryCoordinationStore implements RegistryCoordinationStore {
    private static final String PREFIX = "fulcrum:registry";
    private static final String RESERVE_SCRIPT = """
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        local count = redis.call('ZCARD', KEYS[1])
        local capacity = tonumber(ARGV[3])
        if count >= capacity then
            return 0
        end
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4])
        redis.call('HSET', KEYS[2],
            'serverId', ARGV[5],
            'familyId', ARGV[6],
            'expiresAt', ARGV[2],
            'metadata', ARGV[7])
        redis.call('PEXPIRE', KEYS[1], ARGV[8])
        redis.call('PEXPIRE', KEYS[2], ARGV[8])
        return 1
        """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisRegistryCoordinationStore(MessageBusConnectionConfig config) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withHost(config.getHost())
            .withPort(config.getPort())
            .withDatabase(config.getDatabase())
            .withTimeout(config.getConnectionTimeout());

        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        this.redisClient = RedisClient.create(uriBuilder.build());
        this.connection = redisClient.connect();
        this.connection.sync().ping();
    }

    @Override
    public Optional<CapacityLease> reserveCapacity(
        String serverId,
        String familyId,
        int capacity,
        Duration ttl,
        Map<String, String> metadata
    ) {
        if (capacity <= 0) {
            return Optional.empty();
        }

        String ticketId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long ttlMillis = Math.max(1L, ttl.toMillis());
        long expiresAt = now + ttlMillis;
        String ticketSetKey = ticketSetKey(serverId, familyId);
        String ticketKey = ticketKey(ticketId);
        String metadataJson = writeMetadata(metadata);

        RedisCommands<String, String> commands = connection.sync();
        Long accepted = commands.eval(
            RESERVE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {ticketSetKey, ticketKey},
            String.valueOf(now),
            String.valueOf(expiresAt),
            String.valueOf(capacity),
            ticketId,
            serverId,
            familyId,
            metadataJson,
            String.valueOf(ttlMillis)
        );

        if (accepted == null || accepted == 0L) {
            return Optional.empty();
        }
        return Optional.of(new CapacityLease(ticketId, serverId, familyId, expiresAt, metadata));
    }

    @Override
    public void releaseCapacity(CapacityLease lease) {
        if (lease == null) {
            return;
        }
        RedisCommands<String, String> commands = connection.sync();
        commands.zrem(ticketSetKey(lease.serverId(), lease.familyId()), lease.ticketId());
        commands.del(ticketKey(lease.ticketId()));
    }

    @Override
    public void close() {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    private String ticketSetKey(String serverId, String familyId) {
        return PREFIX + ":capacity:" + serverId + ":" + familyId + ":tickets";
    }

    private String ticketKey(String ticketId) {
        return PREFIX + ":capacity-ticket:" + ticketId;
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to serialize capacity ticket metadata", exception);
        }
    }
}
