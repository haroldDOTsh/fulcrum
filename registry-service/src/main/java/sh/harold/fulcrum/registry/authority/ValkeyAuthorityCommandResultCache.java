package sh.harold.fulcrum.registry.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.CachedAuthorityCommandPort;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ValkeyAuthorityCommandResultCache implements CachedAuthorityCommandPort.CommandResultCache {
    static final String STORE_NAME = "valkey";
    static final String WIRE_PROTOCOL = "redis-compatible";
    private static final String PREFIX = "fulcrum:authority:valkey:idempotency-result:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WRITE_SCRIPT = """
        local existing = redis.call('HGET', KEYS[1], 'commandFingerprint')
        if existing and existing ~= ARGV[1] then
            return 0
        end
        redis.call('HSET', KEYS[1],
            'commandFingerprint', ARGV[1],
            'contractFingerprint', ARGV[2],
            'idempotencyKey', ARGV[3],
            'commandId', ARGV[4],
            'accepted', ARGV[5],
            'revision', ARGV[6],
            'rejectionReason', ARGV[7],
            'message', ARGV[8],
            'settlement', ARGV[9])
        redis.call('PEXPIRE', KEYS[1], ARGV[10])
        return 1
        """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final long ttlMillis;

    public ValkeyAuthorityCommandResultCache(MessageBusConnectionConfig config, Duration ttl) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withHost(config.getHost())
            .withPort(config.getPort())
            .withDatabase(config.getDatabase())
            .withTimeout(config.getConnectionTimeout());

        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }

        this.ttlMillis = Math.max(1L, ttl == null ? Duration.ofHours(24).toMillis() : ttl.toMillis());
        this.redisClient = RedisClient.create(uriBuilder.build());
        this.connection = redisClient.connect();
        this.connection.sync().ping();
    }

    @Override
    public Optional<CachedAuthorityCommandPort.CachedCommandResult> read(String idempotencyKey) {
        Map<String, String> values = connection.sync().hgetall(cacheKey(idempotencyKey));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        String commandFingerprint = values.get("commandFingerprint");
        String contractFingerprint = values.get("contractFingerprint");
        String commandId = values.get("commandId");
        if (commandFingerprint == null || commandFingerprint.isBlank()
            || contractFingerprint == null || contractFingerprint.isBlank()
            || commandId == null || commandId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CachedAuthorityCommandPort.CachedCommandResult(
            value(values, "idempotencyKey", idempotencyKey),
            commandFingerprint,
            contractFingerprint,
            commandResult(
                values,
                UUID.fromString(commandId),
                Boolean.parseBoolean(value(values, "accepted", "false")),
                longValue(values.get("revision"), 0L),
                rejectionReason(values.get("rejectionReason")),
                value(values, "message", "")
            )
        ));
    }

    @Override
    public void write(CachedAuthorityCommandPort.CachedCommandResult result) {
        DataAuthority.CommandResult commandResult = result.result();
        RedisCommands<String, String> commands = connection.sync();
        commands.eval(
            WRITE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] { cacheKey(result.idempotencyKey()) },
            result.commandFingerprint(),
            result.contractFingerprint(),
            result.idempotencyKey(),
            commandResult.commandId().toString(),
            Boolean.toString(commandResult.accepted()),
            Long.toString(commandResult.revision()),
            commandResult.rejectionReason().name(),
            commandResult.message(),
            settlementJson(commandResult.settlement()),
            Long.toString(ttlMillis)
        );
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

    static String cacheKey(String idempotencyKey) {
        String encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded;
    }

    static String writeScript() {
        return WRITE_SCRIPT;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    private static long longValue(String value, long fallback) {
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }

    private static DataAuthority.RejectionReason rejectionReason(String value) {
        return value == null || value.isBlank()
            ? DataAuthority.RejectionReason.NONE
            : DataAuthority.RejectionReason.valueOf(value);
    }

    private static DataAuthority.CommandResult commandResult(
        Map<String, String> values,
        UUID commandId,
        boolean accepted,
        long revision,
        DataAuthority.RejectionReason rejectionReason,
        String message
    ) {
        Map<?, ?> settlementPayload = Map.of();
        String rawSettlement = values.get("settlement");
        if (rawSettlement != null && !rawSettlement.isBlank()) {
            settlementPayload = settlementPayload(rawSettlement);
        }
        return new DataAuthority.CommandResult(
            commandId,
            accepted,
            revision,
            rejectionReason,
            message,
            DataAuthority.CommandSettlement.fromPayload(
                settlementPayload,
                DataAuthority.CommandSettlement.unsettled(revision)
            )
        );
    }

    private static String settlementJson(DataAuthority.CommandSettlement settlement) {
        try {
            return OBJECT_MAPPER.writeValueAsString(settlement.payload());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize command settlement", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> settlementPayload(String rawSettlement) {
        try {
            Object value = OBJECT_MAPPER.readValue(rawSettlement, Map.class);
            return value instanceof Map<?, ?> map ? map : Map.of();
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
