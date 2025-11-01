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
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Sweeps player session state for servers that timed out and ensures data is
 * persisted before removing cache entries.
 */
public class DeadServerSessionSweeper implements AutoCloseable {

    private static final String SWEEP_QUEUE_KEY = "fulcrum:registry:sweeps:queue";
    private static final String SWEEP_PENDING_KEY = "fulcrum:registry:sweeps:pending";
    private static final String SWEEP_LOCK_PREFIX = "fulcrum:registry:sweeps:lock:";
    private static final String SWEEP_RESULT_PREFIX = "fulcrum:registry:sweeps:last:";
    private static final long SWEEP_LOCK_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final long QUEUE_IDLE_SLEEP_MS = 1000;

    private final Logger logger;
    private final ExecutorService executor;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> redisConnection;
    private final MongoClient mongoClient;
    private final MongoCollection<Document> playersCollection;
    private final PlaytimeTracker playtimeTracker;
    private final SessionLogRepository sessionLogRepository;
    private final ObjectMapper objectMapper;
    private final String workerId = UUID.randomUUID().toString();
    private volatile boolean running = true;

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

        if (redisConnection != null) {
            executor.submit(this::processQueue);
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

    public void sweepAsync(String serverId) {
        queueSweep(serverId);
    }

    public void sweep(String serverId) {
        SweepResult result = sweepInternal(serverId);
        if (redisConnection != null) {
            RedisCommands<String, String> commands = redisConnection.sync();
            recordResult(commands, serverId, result);
            commands.srem(SWEEP_PENDING_KEY, serverId);
            releaseLock(commands, serverId);
        }
    }

    private void queueSweep(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }

        if (redisConnection == null) {
            executor.submit(() -> sweepInternal(serverId));
            return;
        }

        RedisCommands<String, String> commands = redisConnection.sync();
        if (commands.sadd(SWEEP_PENDING_KEY, serverId) == 1) {
            commands.rpush(SWEEP_QUEUE_KEY, serverId);
            logger.debug("Queued sweep for server {}", serverId);
        } else {
            logger.debug("Sweep for server {} already pending", serverId);
        }
    }

    private void processQueue() {
        if (redisConnection == null) {
            return;
        }

        RedisCommands<String, String> commands = redisConnection.sync();
        while (running && !Thread.currentThread().isInterrupted()) {
            String serverId = commands.rpop(SWEEP_QUEUE_KEY);
            if (serverId == null) {
                try {
                    Thread.sleep(QUEUE_IDLE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }

            if (!acquireLock(commands, serverId)) {
                commands.rpush(SWEEP_QUEUE_KEY, serverId);
                try {
                    Thread.sleep(QUEUE_IDLE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }

            try {
                SweepResult result = sweepInternal(serverId);
                recordResult(commands, serverId, result);
            } finally {
                releaseLock(commands, serverId);
                commands.srem(SWEEP_PENDING_KEY, serverId);
            }
        }
    }

    private SweepResult sweepInternal(String serverId) {
        logger.info("Sweeping session cache for dead server {}", serverId);
        if (redisConnection == null) {
            logger.warn("Redis connection unavailable; cannot sweep sessions for {}", serverId);
            return SweepResult.empty();
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

        return new SweepResult(processed, persisted, System.currentTimeMillis());
    }

    private boolean acquireLock(RedisCommands<String, String> commands, String serverId) {
        if (commands == null) {
            return true;
        }
        SetArgs args = SetArgs.Builder.nx().px(SWEEP_LOCK_TTL_MS);
        String lockKey = SWEEP_LOCK_PREFIX + serverId;
        String response = commands.set(lockKey, workerId, args);
        return "OK".equalsIgnoreCase(response);
    }

    private void releaseLock(RedisCommands<String, String> commands, String serverId) {
        if (commands == null) {
            return;
        }
        String lockKey = SWEEP_LOCK_PREFIX + serverId;
        String currentOwner = commands.get(lockKey);
        if (workerId.equals(currentOwner)) {
            commands.del(lockKey);
        }
    }

    private void recordResult(RedisCommands<String, String> commands, String serverId, SweepResult result) {
        if (commands == null || result == null) {
            return;
        }
        Map<String, String> payload = new HashMap<>();
        payload.put("processed", Integer.toString(result.processed()));
        payload.put("persisted", Integer.toString(result.persisted()));
        payload.put("finishedAt", Long.toString(result.finishedAt()));
        commands.hset(SWEEP_RESULT_PREFIX + serverId, payload);
    }

    @Override
    public void close() throws Exception {
        running = false;
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
        Integer protocolVersion = record.getClientProtocolVersion();
        if (protocolVersion != null) {
            payload.put("clientProtocolVersion", protocolVersion);
        }
        String brand = record.getClientBrand();
        if (brand != null) {
            payload.put("clientBrand", brand);
        }
        return payload;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Optional.ofNullable(source.get(key)).ifPresent(value -> target.put(key, value));
    }

    private record SweepResult(int processed, int persisted, long finishedAt) {
        static SweepResult empty() {
            return new SweepResult(0, 0, System.currentTimeMillis());
        }
    }

    public record RedisConfig(String host, int port, String password) {
    }

    public record MongoConfig(String connectionString, String database) {
    }

    public record PostgresConfig(boolean enabled, String jdbcUrl, String username, String password, String database) {
    }
}
