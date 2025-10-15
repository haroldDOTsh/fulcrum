package sh.harold.fulcrum.registry.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.Document;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.data.playtime.PlaytimeTracker;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Sweeps player session state for servers that timed out and ensures data is
 * persisted before removing cache entries.
 */
public class DeadServerSessionSweeper implements AutoCloseable {

    private final Logger logger;
    private final ExecutorService executor;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> redisConnection;
    private final MongoClient mongoClient;
    private final MongoCollection<Document> playersCollection;
    private final PlaytimeTracker playtimeTracker;
    private final SessionLogRepository sessionLogRepository;
    private final ObjectMapper objectMapper;

    public DeadServerSessionSweeper(Logger logger,
                                    ExecutorService executor,
                                    RedisConfig redisConfig,
                                    MongoConfig mongoConfig,
                                    PostgresConfig postgresConfig) {
        this.logger = logger;
        this.executor = executor;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        RedisClient client = createRedisClient(redisConfig);
        this.redisClient = client;
        this.redisConnection = client != null ? client.connect() : null;
        this.mongoClient = mongoConfig != null ? MongoClients.create(mongoConfig.connectionString()) : null;
        MongoDatabase database = mongoClient != null ? mongoClient.getDatabase(mongoConfig.database()) : null;
        this.playersCollection = database != null ? database.getCollection("players") : null;
        this.playtimeTracker = database != null ? new PlaytimeTracker(database, null) : null;
        if (playersCollection == null) {
            logger.warn("Session sweeper running without Mongo persistence; configure storage.mongodb to enable");
        }

        this.sessionLogRepository = postgresConfig != null && postgresConfig.enabled()
                ? new SessionLogRepository(new PostgresConnectionAdapter(
                postgresConfig.jdbcUrl(),
                postgresConfig.username(),
                postgresConfig.password(),
                postgresConfig.database()
        ), logger)
                : null;
        if (sessionLogRepository == null) {
            logger.info("Session sweeper Postgres logging disabled");
        }
    }

    private RedisClient createRedisClient(RedisConfig config) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.host())
                .withPort(config.port())
                .withTimeout(Duration.ofSeconds(5));

        if (config.password() != null && !config.password().isBlank()) {
            builder.withPassword(config.password().toCharArray());
        }

        RedisURI uri = builder.build();
        return RedisClient.create(uri);
    }

    public Future<?> sweepAsync(String serverId) {
        return executor.submit(() -> sweep(serverId));
    }

    public void sweep(String serverId) {
        logger.info("Sweeping session cache for dead server {}", serverId);
        if (redisConnection == null) {
            logger.warn("Redis connection unavailable; cannot sweep sessions for {}", serverId);
            return;
        }

        RedisCommands<String, String> commands = redisConnection.sync();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.matches("fulcrum:player:*:state").limit(200);

        int processed = 0;
        int persisted = 0;

        do {
            KeyScanCursor<String> scan = commands.scan(cursor, args);
            for (String key : scan.getKeys()) {
                try {
                    String payload = commands.get(key);
                    if (payload == null || payload.isBlank()) {
                        continue;
                    }

                    PlayerSessionRecord record = objectMapper.readValue(payload, PlayerSessionRecord.class);
                    if (record.getServerId() == null || !record.getServerId().equals(serverId)) {
                        continue;
                    }

                    long endedAt = System.currentTimeMillis();
                    record.closeSegmentsIfNeeded(endedAt);
                    persistRecord(record, endedAt);
                    commands.del(key);
                    persisted++;
                } catch (Exception exception) {
                    logger.warn("Failed to sweep session key {} for {}", key, serverId, exception);
                } finally {
                    processed++;
                }
            }
            cursor = scan;
        } while (!cursor.isFinished());

        logger.info("Session sweep for {} processed {} keys, persisted {}", serverId, processed, persisted);
    }

    private void persistRecord(PlayerSessionRecord record, long endedAt) {
        if (playtimeTracker != null) {
            try {
                playtimeTracker.recordCompletedSegments(record);
            } catch (Exception exception) {
                logger.warn("Failed to process playtime for session {}", record.getSessionId(), exception);
            }
        }

        Map<String, Object> payload = buildPersistencePayload(record);

        if (playersCollection != null) {
            Document update = new Document();
            payload.forEach(update::put);
            playersCollection.updateOne(
                    Filters.eq("_id", record.getPlayerId().toString()),
                    new Document("$set", update),
                    new UpdateOptions().upsert(true)
            );
        }

        if (sessionLogRepository != null) {
            sessionLogRepository.recordSession(record, endedAt);
        }
    }

    private Map<String, Object> buildPersistencePayload(PlayerSessionRecord record) {
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> core = record.getCore();

        // UUID is implicit in the document key; avoid duplicating it.
        copyIfPresent(core, payload, "username");
        copyIfPresent(core, payload, "firstJoin");
        copyIfPresent(core, payload, "lastSeen");
        copyIfPresent(core, payload, "environment");

        if (record.shouldPersistRank()) {
            Map<String, Object> rankInfo = new HashMap<>(record.getRank());
            payload.put("rankInfo", rankInfo);
            Object primary = rankInfo.get("primary");
            if (primary != null && !"DEFAULT".equalsIgnoreCase(primary.toString())) {
                payload.put("rank", primary);
            }
        }

        if (!record.getMinigames().isEmpty()) {
            payload.put("minigames", new HashMap<>(record.getMinigames()));
        }

        payload.put("lastUpdatedAt", record.getLastUpdatedAt());
        return payload;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Optional.ofNullable(source.get(key)).ifPresent(value -> target.put(key, value));
    }

    @Override
    public void close() throws Exception {
        if (redisConnection != null) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (sessionLogRepository != null) {
            sessionLogRepository.close();
        }
    }

    public record RedisConfig(String host, int port, String password) {
    }

    public record MongoConfig(String connectionString, String database) {
    }

    public record PostgresConfig(boolean enabled, String jdbcUrl, String username, String password, String database) {
    }
}
