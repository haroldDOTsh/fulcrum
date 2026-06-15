package sh.harold.fulcrum.registry.authority;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheCodec;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreResult;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRestoreTarget;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.time.Duration;
import java.util.Map;

/**
 * Valkey-backed snapshot-cache projection fed by compacted authority state records.
 */
public final class ValkeyAuthoritySnapshotCacheProjection implements AuthorityStateRestoreTarget, AutoCloseable {
    public static final String STORE_NAME = AuthoritySnapshotCacheCodec.STORE_NAME;
    public static final String WIRE_PROTOCOL = AuthoritySnapshotCacheCodec.WIRE_PROTOCOL;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final long ttlMillis;

    public ValkeyAuthoritySnapshotCacheProjection(MessageBusConnectionConfig config, Duration ttl) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withHost(config.getHost())
            .withPort(config.getPort())
            .withDatabase(config.getDatabase())
            .withTimeout(config.getConnectionTimeout());

        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        this.ttlMillis = ttl == null ? Duration.ofMinutes(5).toMillis() : ttl.toMillis();
        this.redisClient = RedisClient.create(uriBuilder.build());
        this.connection = redisClient.connect();
        this.connection.sync().ping();
    }

    @Override
    public String projectionName() {
        return AuthoritySnapshotCacheCodec.PROJECTION_NAME;
    }

    @Override
    public String projectionVersion() {
        return "v1";
    }

    @Override
    public AuthorityStateRestoreResult restore(AuthorityStateRecord record) {
        Map<String, String> fields = AuthoritySnapshotCacheCodec.fields(record, System.currentTimeMillis());
        RedisCommands<String, String> commands = connection.sync();
        String key = AuthoritySnapshotCacheCodec.cacheKey(record.aggregateType(), record.aggregateScope());
        commands.hset(key, fields);
        if (ttlMillis > 0L) {
            commands.pexpire(key, ttlMillis);
        }
        return AuthorityStateRestoreResult.restored(projectionVersion(), record);
    }

    public void validateConnection() {
        connection.sync().ping();
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
}
