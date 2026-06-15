package sh.harold.fulcrum.api.data.impl.authority.events;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cassandra-backed hot state projection for authority profile and rank reads.
 */
public final class CassandraAuthorityHotStateProjection implements DataAuthority.PlayerProfileReader,
    DataAuthority.PlayerRankReader,
    AuthorityEventDispatchTarget,
    AuthorityStateRestoreTarget {
    public static final String PROJECTION_NAME = "cassandra-authority-hot-state";
    public static final String PROJECTION_VERSION = "cassandra-authority-hot-state-v1";
    public static final String SCHEMA_RESOURCE = "cassandra/001_create_authority_hot_state.cql";
    public static final String PROFILE_TABLE = "authority_player_profiles_by_player";
    public static final String RANK_TABLE = "authority_player_ranks_by_player";
    public static final String MATCH_TABLE = "authority_live_matches_by_match";

    private static final String PLAYER_PROFILE = "player_profile";
    private static final String PLAYER_RANK = "player_rank";
    private static final String MATCH = "match";
    private static final String UNKNOWN = "unknown";
    private static final List<String> PROFILE_EVENT_TYPES = List.of(
        "RECORD_PLAYER_LOGIN",
        "RECORD_PLAYER_LOGOUT"
    );
    private static final List<String> RANK_EVENT_TYPES = List.of("GRANT_RANK", "REVOKE_RANK");
    private static final List<String> MATCH_EVENT_TYPES = List.of("RECORD_MATCH_START", "RECORD_MATCH_END");
    private static final AuthorityProjectionManifest MANIFEST = AuthorityProjectionManifest.of(
        PROJECTION_NAME,
        PROJECTION_VERSION,
        acceptedEventTypes()
    );
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final CqlSession session;
    private final String keyspace;
    private final Gson gson = new Gson();

    public CassandraAuthorityHotStateProjection(CqlSession session, String keyspace) {
        this.session = Objects.requireNonNull(session, "session");
        this.keyspace = requireIdentifier(keyspace, "keyspace");
    }

    public void validateSchema() {
        validateReadable(PROFILE_TABLE);
        validateReadable(RANK_TABLE);
        validateReadable(MATCH_TABLE);
    }

    @Override
    public String consumerName() {
        return PROJECTION_NAME;
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public String projectionVersion() {
        return PROJECTION_VERSION;
    }

    @Override
    public AuthorityProjectionManifest projectionManifest() {
        return MANIFEST;
    }

    @Override
    public AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) {
        try {
            AuthorityStateRecord record = stateRecord(event);
            AuthorityStateRestoreResult result = restore(record);
            if (result.restored() || "existing projection is newer or equal".equals(result.message())) {
                return AuthorityEventDispatchResult.success(PROJECTION_VERSION, record.stateFingerprint());
            }
            return AuthorityEventDispatchResult.quarantine(result.message());
        } catch (IllegalArgumentException exception) {
            return AuthorityEventDispatchResult.quarantine(exception.getMessage());
        }
    }

    public AuthorityStateRestoreResult restore(AuthorityStateRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.hasValidStateFingerprint()) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state fingerprint mismatch");
        }
        return switch (record.aggregateType()) {
            case PLAYER_PROFILE -> restoreProfile(record);
            case PLAYER_RANK -> restoreRank(record);
            case MATCH -> restoreMatch(record);
            default -> AuthorityStateRestoreResult.skipped(
                PROJECTION_VERSION,
                record,
                "unsupported aggregate type " + record.aggregateType()
            );
        };
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(loadProfile(playerId));
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return DataAuthority.PlayerProfileReader.super.quoteProfile(playerId, requirement)
            .thenApply(this::withHotStateProvenance);
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(loadRanks(playerId));
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return DataAuthority.PlayerRankReader.super.quoteRanks(playerId, requirement)
            .thenApply(this::withHotStateProvenance);
    }

    public List<CassandraProjectionTable> tables() {
        return List.of(
            new CassandraProjectionTable(PROFILE_TABLE, "player_id"),
            new CassandraProjectionTable(RANK_TABLE, "player_id"),
            new CassandraProjectionTable(MATCH_TABLE, "match_id")
        );
    }

    private <T> DataAuthority.QuotedRead<T> withHotStateProvenance(DataAuthority.QuotedRead<T> read) {
        DataAuthority.ReadQuote quote = read.quote();
        DataAuthority.ReadQuote hotStateQuote = new DataAuthority.ReadQuote(
            quote.aggregateScope(),
            quote.projectionFamily(),
            quote.requiredRevision(),
            quote.observedRevision(),
            quote.status(),
            quote.watermark(),
            quote.message(),
            DataAuthority.ReadProvenance.hotState(),
            quote.deliveryReceipt()
        );
        return read.satisfied()
            ? DataAuthority.QuotedRead.satisfied(read.snapshot().orElseThrow(), hotStateQuote)
            : DataAuthority.QuotedRead.unsatisfied(hotStateQuote);
    }

    private AuthorityStateRecord stateRecord(AuthorityEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        String eventType = event.eventType().trim();
        Map<String, Object> statePayload;
        if (PROFILE_EVENT_TYPES.contains(eventType)) {
            statePayload = profileStatePayload(event, eventType);
        } else if (RANK_EVENT_TYPES.contains(eventType)) {
            statePayload = rankStatePayload(event);
        } else if (MATCH_EVENT_TYPES.contains(eventType)) {
            statePayload = matchStatePayload(event);
        } else {
            throw new IllegalArgumentException("Unsupported Cassandra hot-state event type " + eventType);
        }

        Map<?, ?> route = route(event);
        String commandDomain = string(route.get("domain"), event.aggregateType());
        String stateTopic = string(route.get("stateTopic"), "state." + commandDomain);
        String partitionKey = string(route.get("partitionKey"), event.aggregateScope());
        return new AuthorityStateRecord(
            event.commandId(),
            event.eventId(),
            event.aggregateScope(),
            event.aggregateType(),
            event.aggregateId(),
            event.revision(),
            commandDomain,
            stateTopic,
            partitionKey,
            statePayload,
            AuthorityStateRecord.stateFingerprint(statePayload),
            event.eventChainHash(),
            event.createdAt()
        );
    }

    private Map<String, Object> profileStatePayload(AuthorityEventEnvelope event, String eventType) {
        requireAggregateType(event, PLAYER_PROFILE);
        UUID playerId = playerId(event);
        DataAuthority.PlayerProfileSnapshot current = loadProfile(playerId).orElse(null);
        if (current != null && current.revision() > event.revision()) {
            return profilePayload(current);
        }

        Map<?, ?> payload = commandPayload(event);
        boolean online = switch (eventType) {
            case "RECORD_PLAYER_LOGIN" -> true;
            case "RECORD_PLAYER_LOGOUT" -> false;
            default -> throw new IllegalArgumentException("Unsupported profile event type " + eventType);
        };
        String username = boundedUsername(string(payload.get("username"), current == null ? UNKNOWN : current.username()));
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        String currentServer = online ? string(payload.get("currentServer"), null) : null;
        String currentProxy = online ? string(payload.get("currentProxy"), null) : null;
        long totalPlaytimeMs = current == null ? 0L : current.totalPlaytimeMs();
        Map<String, Object> profileData = mergeProfileData(current, payload);
        return profilePayload(
            playerId,
            username,
            normalizedUsername,
            online,
            currentServer,
            currentProxy,
            totalPlaytimeMs,
            profileData,
            event.revision()
        );
    }

    private Map<String, Object> rankStatePayload(AuthorityEventEnvelope event) {
        requireAggregateType(event, PLAYER_RANK);
        UUID playerId = playerId(event);
        DataAuthority.PlayerRankSnapshot current = loadRanks(playerId).orElse(null);
        if (current != null && current.revision() > event.revision()) {
            return rankPayload(current);
        }

        Map<?, ?> payload = commandPayload(event);
        String primaryRank = string(payload.get("primaryRank"), current == null ? "DEFAULT" : current.primaryRank());
        List<String> projectedRanks = strings(payload.get("ranks"));
        if (projectedRanks.isEmpty()) {
            projectedRanks = List.of(primaryRank);
        }
        return rankPayload(playerId, primaryRank, projectedRanks, event.revision());
    }

    private Map<String, Object> matchStatePayload(AuthorityEventEnvelope event) {
        requireAggregateType(event, MATCH);
        UUID matchId = matchId(event);
        Map<String, Object> current = loadStatePayload(MATCH_TABLE, "match_id", matchId).orElse(null);
        if (current != null && longValue(current.get("revision"), 0L) > event.revision()) {
            return current;
        }

        Map<?, ?> payload = commandPayload(event);
        String defaultState = "RECORD_MATCH_END".equals(event.eventType().trim()) ? "ENDED" : "STARTED";
        return matchPayload(
            matchId,
            string(payload.get("familyId"), UNKNOWN),
            string(payload.get("mapId"), null),
            string(payload.get("serverId"), null),
            string(payload.get("slotId"), null),
            string(payload.get("state"), defaultState),
            longObject(payload.get("startedAt")),
            longObject(payload.get("endedAt")),
            stringObjectMap(payload.get("slotMetadata")),
            objects(payload.get("participants")),
            event.revision()
        );
    }

    private AuthorityStateRestoreResult restoreProfile(AuthorityStateRecord record) {
        UUID playerId = playerId(record);
        if (playerId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing playerId");
        }
        return writeGuarded(PROFILE_TABLE, "player_id", playerId, record)
            ? AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record)
            : AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "existing projection is newer or equal");
    }

    private AuthorityStateRestoreResult restoreRank(AuthorityStateRecord record) {
        UUID playerId = playerId(record);
        if (playerId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing playerId");
        }
        return writeGuarded(RANK_TABLE, "player_id", playerId, record)
            ? AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record)
            : AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "existing projection is newer or equal");
    }

    private AuthorityStateRestoreResult restoreMatch(AuthorityStateRecord record) {
        UUID matchId = matchId(record);
        if (matchId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing matchId");
        }
        return writeGuarded(MATCH_TABLE, "match_id", matchId, record)
            ? AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record)
            : AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "existing projection is newer or equal");
    }

    private boolean writeGuarded(String table, String keyColumn, UUID keyValue, AuthorityStateRecord record) {
        if (applied(session.execute(insertIfAbsent(table, keyColumn, keyValue, record)))) {
            return true;
        }
        return applied(session.execute(updateIfNewer(table, keyColumn, keyValue, record)));
    }

    private SimpleStatement insertIfAbsent(String table, String keyColumn, UUID keyValue, AuthorityStateRecord record) {
        return SimpleStatement.newInstance("""
            INSERT INTO %s.%s (
                %s, aggregate_scope, aggregate_type, aggregate_id, revision,
                command_domain, state_topic, partition_key, source_partition, source_offset,
                source_command_id, source_event_id, state_payload, state_fingerprint,
                event_chain_hash, event_created_at, projection_version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()))
            IF NOT EXISTS
            """.formatted(keyspace, table, keyColumn),
            keyValue,
            record.aggregateScope(),
            record.aggregateType(),
            record.aggregateId(),
            record.revision(),
            record.commandDomain(),
            record.stateTopic(),
            record.partitionKey(),
            record.sourcePartition(),
            record.sourceOffset(),
            record.commandId(),
            record.eventId(),
            gson.toJson(record.statePayload()),
            record.stateFingerprint(),
            record.eventChainHash(),
            record.eventCreatedAt(),
            PROJECTION_VERSION
        );
    }

    private SimpleStatement updateIfNewer(String table, String keyColumn, UUID keyValue, AuthorityStateRecord record) {
        return SimpleStatement.newInstance("""
            UPDATE %s.%s
            SET aggregate_scope = ?,
                aggregate_type = ?,
                aggregate_id = ?,
                revision = ?,
                command_domain = ?,
                state_topic = ?,
                partition_key = ?,
                source_partition = ?,
                source_offset = ?,
                source_command_id = ?,
                source_event_id = ?,
                state_payload = ?,
                state_fingerprint = ?,
                event_chain_hash = ?,
                event_created_at = ?,
                projection_version = ?,
                updated_at = toTimestamp(now())
            WHERE %s = ?
            IF revision < ?
            """.formatted(keyspace, table, keyColumn),
            record.aggregateScope(),
            record.aggregateType(),
            record.aggregateId(),
            record.revision(),
            record.commandDomain(),
            record.stateTopic(),
            record.partitionKey(),
            record.sourcePartition(),
            record.sourceOffset(),
            record.commandId(),
            record.eventId(),
            gson.toJson(record.statePayload()),
            record.stateFingerprint(),
            record.eventChainHash(),
            record.eventCreatedAt(),
            PROJECTION_VERSION,
            keyValue,
            record.revision()
        );
    }

    private SimpleStatement selectByPlayer(String table, UUID playerId) {
        return selectByKey(table, "player_id", playerId);
    }

    private SimpleStatement selectByKey(String table, String keyColumn, UUID keyValue) {
        return SimpleStatement.newInstance("""
            SELECT %s, aggregate_scope, aggregate_type, aggregate_id, revision,
                   command_domain, state_topic, partition_key, source_partition, source_offset,
                   source_command_id, source_event_id, state_payload, state_fingerprint,
                   event_chain_hash, event_created_at
            FROM %s.%s
            WHERE %s = ?
            """.formatted(keyColumn, keyspace, table, keyColumn), keyValue);
    }

    private void validateReadable(String table) {
        validateReadable(table, table.equals(MATCH_TABLE) ? "match_id" : "player_id");
    }

    private void validateReadable(String table, String keyColumn) {
        session.execute(SimpleStatement.newInstance(
            "SELECT %s FROM %s.%s LIMIT 1".formatted(keyColumn, keyspace, table)
        ));
    }

    private Optional<DataAuthority.PlayerProfileSnapshot> loadProfile(UUID playerId) {
        Row row = session.execute(selectByPlayer(PROFILE_TABLE, playerId)).one();
        return row == null ? Optional.empty() : Optional.of(profileSnapshot(row));
    }

    private Optional<DataAuthority.PlayerRankSnapshot> loadRanks(UUID playerId) {
        Row row = session.execute(selectByPlayer(RANK_TABLE, playerId)).one();
        return row == null ? Optional.empty() : Optional.of(rankSnapshot(row));
    }

    private Optional<Map<String, Object>> loadStatePayload(String table, String keyColumn, UUID keyValue) {
        Row row = session.execute(selectByKey(table, keyColumn, keyValue)).one();
        return row == null ? Optional.empty() : Optional.of(statePayload(row));
    }

    private DataAuthority.PlayerProfileSnapshot profileSnapshot(Row row) {
        Map<String, Object> payload = statePayload(row);
        UUID playerId = row.getUuid("player_id");
        String username = string(payload.get("username"), "unknown");
        return new DataAuthority.PlayerProfileSnapshot(
            playerId,
            username,
            string(payload.get("normalizedUsername"), username.toLowerCase(Locale.ROOT)),
            booleanValue(payload.get("online")),
            string(payload.get("currentServer"), null),
            string(payload.get("currentProxy"), null),
            longValue(payload.get("totalPlaytimeMs"), 0L),
            stringObjectMap(payload.get("profileData")),
            longValue(payload.get("revision"), row.getLong("revision")),
            watermark(row)
        );
    }

    private DataAuthority.PlayerRankSnapshot rankSnapshot(Row row) {
        Map<String, Object> payload = statePayload(row);
        UUID playerId = row.getUuid("player_id");
        String primaryRank = string(payload.get("primaryRank"), "DEFAULT");
        List<String> ranks = strings(payload.get("ranks"));
        return new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            ranks.isEmpty() ? List.of(primaryRank) : ranks,
            longValue(payload.get("revision"), row.getLong("revision")),
            watermark(row)
        );
    }

    private DataAuthority.SnapshotWatermark watermark(Row row) {
        return new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            row.getString("aggregate_scope"),
            row.getString("aggregate_type"),
            row.getString("aggregate_id"),
            row.getString("command_domain"),
            row.getString("state_topic"),
            row.getString("partition_key"),
            row.getUuid("source_command_id"),
            row.getUuid("source_event_id"),
            row.getLong("revision"),
            instant(row, "event_created_at").toEpochMilli(),
            row.getInt("source_partition"),
            row.getLong("source_offset"),
            row.getString("state_fingerprint"),
            row.getString("event_chain_hash")
        );
    }

    private Map<String, Object> statePayload(Row row) {
        Map<String, Object> parsed = gson.fromJson(row.getString("state_payload"), MAP_TYPE);
        return parsed == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
    }

    private static Map<String, Object> profilePayload(
        UUID playerId,
        String username,
        String normalizedUsername,
        boolean online,
        String currentServer,
        String currentProxy,
        long totalPlaytimeMs,
        Map<String, Object> profileData,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("username", username);
        values.put("normalizedUsername", normalizedUsername);
        values.put("online", online);
        values.put("currentServer", currentServer);
        values.put("currentProxy", currentProxy);
        values.put("totalPlaytimeMs", totalPlaytimeMs);
        values.put("profileData", profileData == null ? Map.of() : profileData);
        values.put("revision", revision);
        return java.util.Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> profilePayload(DataAuthority.PlayerProfileSnapshot snapshot) {
        return profilePayload(
            snapshot.playerId(),
            snapshot.username(),
            snapshot.normalizedUsername(),
            snapshot.online(),
            snapshot.currentServer(),
            snapshot.currentProxy(),
            snapshot.totalPlaytimeMs(),
            snapshot.profileData(),
            snapshot.revision()
        );
    }

    private static Map<String, Object> rankPayload(UUID playerId, String primaryRank, List<String> ranks, long revision) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("primaryRank", primaryRank);
        values.put("ranks", ranks);
        values.put("revision", revision);
        return Map.copyOf(values);
    }

    private static Map<String, Object> rankPayload(DataAuthority.PlayerRankSnapshot snapshot) {
        return rankPayload(snapshot.playerId(), snapshot.primaryRank(), snapshot.ranks(), snapshot.revision());
    }

    private static Map<String, Object> matchPayload(
        UUID matchId,
        String familyId,
        String mapId,
        String serverId,
        String slotId,
        String state,
        Long startedAt,
        Long endedAt,
        Map<String, Object> metadata,
        List<Object> participants,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("matchId", matchId.toString());
        values.put("familyId", familyId);
        values.put("mapId", mapId);
        values.put("serverId", serverId);
        values.put("slotId", slotId);
        values.put("state", state);
        values.put("startedAt", startedAt);
        values.put("endedAt", endedAt);
        values.put("metadata", metadata == null ? Map.of() : metadata);
        values.put("participants", participants == null ? List.of() : participants);
        values.put("revision", revision);
        return java.util.Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> mergeProfileData(
        DataAuthority.PlayerProfileSnapshot current,
        Map<?, ?> payload
    ) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current.profileData());
        }
        payload.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key.toString(), value);
            }
        });
        return Map.copyOf(merged);
    }

    private UUID playerId(AuthorityStateRecord record) {
        Object rawPlayerId = record.statePayload().get("playerId");
        if (rawPlayerId != null && !rawPlayerId.toString().isBlank()) {
            return UUID.fromString(rawPlayerId.toString());
        }
        if (record.aggregateId() == null || record.aggregateId().isBlank()) {
            return null;
        }
        return UUID.fromString(record.aggregateId());
    }

    private UUID matchId(AuthorityStateRecord record) {
        Object rawMatchId = record.statePayload().get("matchId");
        if (rawMatchId != null && !rawMatchId.toString().isBlank()) {
            return UUID.fromString(rawMatchId.toString());
        }
        if (record.aggregateId() == null || record.aggregateId().isBlank()) {
            return null;
        }
        return UUID.fromString(record.aggregateId());
    }

    private static UUID playerId(AuthorityEventEnvelope event) {
        UUID aggregateId = uuid(event.aggregateId());
        if (aggregateId != null) {
            return aggregateId;
        }
        UUID payloadPlayerId = uuid(commandPayload(event).get("playerId"));
        if (payloadPlayerId != null) {
            return payloadPlayerId;
        }
        throw new IllegalArgumentException("Cassandra hot-state event is missing playerId");
    }

    private static UUID matchId(AuthorityEventEnvelope event) {
        UUID aggregateId = uuid(event.aggregateId());
        if (aggregateId != null) {
            return aggregateId;
        }
        UUID payloadMatchId = uuid(commandPayload(event).get("matchId"));
        if (payloadMatchId != null) {
            return payloadMatchId;
        }
        throw new IllegalArgumentException("Cassandra hot-state event is missing matchId");
    }

    private static void requireAggregateType(AuthorityEventEnvelope event, String expectedAggregateType) {
        if (!expectedAggregateType.equals(event.aggregateType())) {
            throw new IllegalArgumentException(
                "Cassandra hot-state event aggregate type mismatch: expected "
                    + expectedAggregateType + " but received " + event.aggregateType()
            );
        }
    }

    private static Map<?, ?> commandPayload(AuthorityEventEnvelope event) {
        Object payload = event.payload().get("payload");
        return payload instanceof Map<?, ?> map ? map : event.payload();
    }

    private static Map<?, ?> route(AuthorityEventEnvelope event) {
        Object route = event.payload().get("route");
        return route instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean applied(ResultSet resultSet) {
        Row row = resultSet.one();
        return row != null && row.getBoolean("[applied]");
    }

    private static Instant instant(Row row, String column) {
        Instant value = row.getInstant(column);
        return value == null ? Instant.EPOCH : value;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
    }

    private static Long longObject(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || value.toString().isBlank() ? null : Long.parseLong(value.toString());
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String boundedUsername(String username) {
        String effectiveUsername = string(username, UNKNOWN);
        return effectiveUsername.length() > 16 ? effectiveUsername.substring(0, 16) : effectiveUsername;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                values.add(item.toString());
            }
        }
        return List.copyOf(values);
    }

    private static List<Object> objects(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                values.add(item);
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> stringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, child) -> {
            if (key != null) {
                result.put(key.toString(), child);
            }
        });
        return Map.copyOf(result);
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be a Cassandra identifier");
        }
        return value;
    }

    private static List<String> acceptedEventTypes() {
        java.util.ArrayList<String> values = new java.util.ArrayList<>(PROFILE_EVENT_TYPES);
        values.addAll(RANK_EVENT_TYPES);
        values.addAll(MATCH_EVENT_TYPES);
        return List.copyOf(values);
    }

    public record CassandraProjectionTable(String table, String keyColumn) {
        public CassandraProjectionTable {
            table = requireIdentifier(table, "table");
            keyColumn = requireIdentifier(keyColumn, "keyColumn");
        }
    }
}
