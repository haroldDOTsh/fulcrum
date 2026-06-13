package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * PostgreSQL-backed command port for the authority write path.
 */
public final class PostgresAuthorityCommandPort implements DataAuthority.CommandPort {
    private final PostgresConnectionAdapter connectionAdapter;
    private final Executor executor;
    private final Gson gson = new Gson();
    private final Object schemaLock = new Object();
    private volatile boolean schemaReady;

    public PostgresAuthorityCommandPort(PostgresConnectionAdapter connectionAdapter) {
        this(connectionAdapter, ForkJoinPool.commonPool());
    }

    public PostgresAuthorityCommandPort(PostgresConnectionAdapter connectionAdapter, Executor executor) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.CommandEnvelope command) {
        return CompletableFuture.supplyAsync(() -> execute(command), executor);
    }

    private DataAuthority.CommandResult execute(DataAuthority.CommandEnvelope command) {
        try (Connection connection = connectionAdapter.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureTablesOnce(connection);

                DataAuthority.CommandResult existing = findExistingResult(connection, command.idempotencyKey());
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                if (isExpired(command)) {
                    DataAuthority.CommandResult result = rejected(command,
                        DataAuthority.RejectionReason.EXPIRED_DEADLINE, "Command deadline expired");
                    recordCommand(connection, command, result);
                    connection.commit();
                    return result;
                }

                DataAuthority.CommandResult result = switch (command.type()) {
                    case RECORD_PLAYER_LOGIN, START_SESSION -> persistPlayerProfile(connection, command, true);
                    case RENEW_SESSION -> persistPlayerProfile(connection, command, true);
                    case RECORD_PLAYER_LOGOUT, END_SESSION -> persistPlayerProfile(connection, command, false);
                    case GRANT_RANK, REVOKE_RANK -> persistRankProjection(connection, command);
                    case RECORD_MATCH_START -> persistMatchStart(connection, command);
                    case RECORD_MATCH_END -> persistMatchEnd(connection, command);
                };

                recordCommand(connection, command, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            return rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE,
                "Postgres authority command failed: " + exception.getMessage());
        }
    }

    private DataAuthority.CommandResult persistPlayerProfile(
        Connection connection,
        DataAuthority.CommandEnvelope command,
        boolean online
    ) throws SQLException {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "playerId is required");
        }

        Map<String, Object> payload = command.payload();
        String username = string(payload, "username", "unknown");
        if (username.length() > 16) {
            username = username.substring(0, 16);
        }

        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        Timestamp timestamp = timestamp(longValue(payload, "timestamp", System.currentTimeMillis()));
        String currentServer = online ? string(payload, "currentServer", null) : null;
        String currentProxy = online ? string(payload, "currentProxy", null) : null;
        String lastIp = string(payload, "lastIp", null);
        String profileJson = gson.toJson(payload);

        String sql = """
            INSERT INTO player_profiles (
                player_id, username, normalized_username, first_seen, last_seen, online,
                current_server, current_proxy, last_ip, profile_data, revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1, ?)
            ON CONFLICT (player_id) DO UPDATE SET
                username = EXCLUDED.username,
                normalized_username = EXCLUDED.normalized_username,
                last_seen = EXCLUDED.last_seen,
                online = EXCLUDED.online,
                current_server = EXCLUDED.current_server,
                current_proxy = EXCLUDED.current_proxy,
                last_ip = COALESCE(EXCLUDED.last_ip, player_profiles.last_ip),
                profile_data = player_profiles.profile_data || EXCLUDED.profile_data,
                revision = player_profiles.revision + 1,
                updated_at = EXCLUDED.updated_at
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            statement.setString(2, username);
            statement.setString(3, normalizedUsername);
            statement.setTimestamp(4, timestamp);
            statement.setTimestamp(5, timestamp);
            statement.setBoolean(6, online);
            setNullableString(statement, 7, currentServer);
            setNullableString(statement, 8, currentProxy);
            setNullableString(statement, 9, lastIp);
            statement.setString(10, profileJson);
            statement.setTimestamp(11, timestamp);
            statement.executeUpdate();
        }

        if (command.type() == DataAuthority.CommandType.START_SESSION
            || command.type() == DataAuthority.CommandType.RENEW_SESSION
            || command.type() == DataAuthority.CommandType.END_SESSION) {
            persistPlayerSession(connection, command, playerId, timestamp);
        }

        return accepted(command, online ? "Player profile/session updated" : "Player profile/session closed");
    }

    private void persistPlayerSession(
        Connection connection,
        DataAuthority.CommandEnvelope command,
        UUID playerId,
        Timestamp timestamp
    ) throws SQLException {
        UUID sessionId = uuid(string(command.payload(), "sessionId", null));
        if (sessionId == null) {
            return;
        }

        boolean ending = command.type() == DataAuthority.CommandType.END_SESSION;
        String state = ending ? "ENDED" : "ACTIVE";
        String proxyId = string(command.payload(), "currentProxy", null);
        String serverId = string(command.payload(), "currentServer", null);
        String disconnectReason = string(command.payload(), "disconnectReason", null);

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_sessions (
                session_id, player_id, proxy_id, server_id, state, started_at,
                last_seen_at, ended_at, disconnect_reason, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (session_id) DO UPDATE SET
                proxy_id = COALESCE(EXCLUDED.proxy_id, player_sessions.proxy_id),
                server_id = COALESCE(EXCLUDED.server_id, player_sessions.server_id),
                state = EXCLUDED.state,
                last_seen_at = EXCLUDED.last_seen_at,
                ended_at = COALESCE(EXCLUDED.ended_at, player_sessions.ended_at),
                disconnect_reason = COALESCE(EXCLUDED.disconnect_reason, player_sessions.disconnect_reason),
                metadata = player_sessions.metadata || EXCLUDED.metadata
            """)) {
            statement.setObject(1, sessionId);
            statement.setObject(2, playerId);
            setNullableString(statement, 3, proxyId);
            setNullableString(statement, 4, serverId);
            statement.setString(5, state);
            statement.setTimestamp(6, timestamp);
            statement.setTimestamp(7, timestamp);
            if (ending) {
                statement.setTimestamp(8, timestamp);
            } else {
                statement.setNull(8, Types.TIMESTAMP);
            }
            setNullableString(statement, 9, disconnectReason);
            statement.setString(10, gson.toJson(command.payload()));
            statement.executeUpdate();
        }
    }

    private DataAuthority.CommandResult persistRankProjection(
        Connection connection,
        DataAuthority.CommandEnvelope command
    ) throws SQLException {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "playerId is required");
        }

        String primaryRank = string(command.payload(), "primaryRank", "DEFAULT");
        List<String> ranks = stringList(command.payload().get("ranks"));
        if (ranks.isEmpty()) {
            ranks = List.of(primaryRank);
        }

        String sql = """
            INSERT INTO player_rank_projection (player_id, primary_rank, ranks, revision, updated_at)
            VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
            ON CONFLICT (player_id) DO UPDATE SET
                primary_rank = EXCLUDED.primary_rank,
                ranks = EXCLUDED.ranks,
                revision = player_rank_projection.revision + 1,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            statement.setString(2, primaryRank);
            statement.setArray(3, connection.createArrayOf("text", ranks.toArray(String[]::new)));
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_rank_audit (audit_id, player_id, rank, action, actor, metadata)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, playerId);
            statement.setString(3, primaryRank);
            statement.setString(4, command.type().name());
            statement.setString(5, command.actorId());
            statement.setString(6, gson.toJson(command.payload()));
            statement.executeUpdate();
        }

        return accepted(command, "Rank projection updated");
    }

    private DataAuthority.CommandResult persistMatchStart(
        Connection connection,
        DataAuthority.CommandEnvelope command
    ) throws SQLException {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "matchId is required");
        }

        Map<String, Object> payload = command.payload();
        Timestamp startedAt = timestamp(longValue(payload, "startedAt",
            longValue(payload, "timestamp", System.currentTimeMillis())));

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO match_records (
                match_id, family_id, map_id, server_id, slot_id, state, started_at, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (match_id) DO UPDATE SET
                family_id = EXCLUDED.family_id,
                map_id = COALESCE(EXCLUDED.map_id, match_records.map_id),
                server_id = COALESCE(EXCLUDED.server_id, match_records.server_id),
                slot_id = COALESCE(EXCLUDED.slot_id, match_records.slot_id),
                state = EXCLUDED.state,
                started_at = COALESCE(match_records.started_at, EXCLUDED.started_at),
                metadata = match_records.metadata || EXCLUDED.metadata
            """)) {
            statement.setObject(1, matchId);
            statement.setString(2, string(payload, "familyId", "unknown"));
            setNullableString(statement, 3, string(payload, "mapId", null));
            setNullableString(statement, 4, string(payload, "serverId", null));
            setNullableString(statement, 5, string(payload, "slotId", null));
            statement.setString(6, string(payload, "state", "STARTED"));
            statement.setTimestamp(7, startedAt);
            statement.setString(8, gson.toJson(payload));
            statement.executeUpdate();
        }

        return accepted(command, "Match record started");
    }

    private DataAuthority.CommandResult persistMatchEnd(
        Connection connection,
        DataAuthority.CommandEnvelope command
    ) throws SQLException {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "matchId is required");
        }

        Map<String, Object> payload = command.payload();
        Timestamp endedAt = timestamp(longValue(payload, "endedAt",
            longValue(payload, "timestamp", System.currentTimeMillis())));

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO match_records (
                match_id, family_id, map_id, server_id, slot_id, state, started_at, ended_at, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (match_id) DO UPDATE SET
                family_id = EXCLUDED.family_id,
                map_id = COALESCE(EXCLUDED.map_id, match_records.map_id),
                server_id = COALESCE(EXCLUDED.server_id, match_records.server_id),
                slot_id = COALESCE(EXCLUDED.slot_id, match_records.slot_id),
                state = EXCLUDED.state,
                ended_at = EXCLUDED.ended_at,
                metadata = match_records.metadata || EXCLUDED.metadata
            """)) {
            statement.setObject(1, matchId);
            statement.setString(2, string(payload, "familyId", "unknown"));
            setNullableString(statement, 3, string(payload, "mapId", null));
            setNullableString(statement, 4, string(payload, "serverId", null));
            setNullableString(statement, 5, string(payload, "slotId", null));
            statement.setString(6, string(payload, "state", "ENDED"));
            statement.setTimestamp(7, endedAt);
            statement.setTimestamp(8, endedAt);
            statement.setString(9, gson.toJson(payload));
            statement.executeUpdate();
        }

        persistMatchParticipants(connection, matchId, payload.get("participants"));
        return accepted(command, "Match record ended");
    }

    private void persistMatchParticipants(Connection connection, UUID matchId, Object rawParticipants)
        throws SQLException {
        if (!(rawParticipants instanceof Iterable<?> participants)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO match_participant_stats (match_id, player_id, team_id, placement, stats)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (match_id, player_id) DO UPDATE SET
                team_id = COALESCE(EXCLUDED.team_id, match_participant_stats.team_id),
                placement = COALESCE(EXCLUDED.placement, match_participant_stats.placement),
                stats = match_participant_stats.stats || EXCLUDED.stats
            """)) {
            for (Object raw : participants) {
                if (!(raw instanceof Map<?, ?> participant)) {
                    continue;
                }
                UUID playerId = uuid(objectString(participant.get("playerId")));
                if (playerId == null) {
                    continue;
                }

                Map<String, Object> stats = new HashMap<>();
                Object rawStats = participant.get("stats");
                if (rawStats instanceof Map<?, ?> rawStatsMap) {
                    for (Map.Entry<?, ?> entry : rawStatsMap.entrySet()) {
                        if (entry.getKey() != null) {
                            stats.put(entry.getKey().toString(), entry.getValue());
                        }
                    }
                }
                Object state = participant.get("state");
                if (state != null) {
                    stats.put("state", state.toString());
                }

                statement.setObject(1, matchId);
                statement.setObject(2, playerId);
                setNullableString(statement, 3, objectString(participant.get("teamId")));
                Integer placement = integer(participant.get("placement"));
                if (placement == null) {
                    statement.setNull(4, Types.INTEGER);
                } else {
                    statement.setInt(4, placement);
                }
                statement.setString(5, gson.toJson(stats));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private DataAuthority.CommandResult findExistingResult(Connection connection, String idempotencyKey)
        throws SQLException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT command_id, accepted, rejection_reason, result_message, result_payload, result_revision
            FROM authority_commands
            WHERE idempotency_key = ?
            """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                UUID commandId = resultSet.getObject("command_id", UUID.class);
                boolean accepted = resultSet.getBoolean("accepted");
                String rejection = resultSet.getString("rejection_reason");
                DataAuthority.RejectionReason reason = rejection == null
                    ? null
                    : DataAuthority.RejectionReason.valueOf(rejection);
                return new DataAuthority.CommandResult(
                    commandId,
                    accepted,
                    resultSet.getLong("result_revision"),
                    reason,
                    resultSet.getString("result_message")
                );
            }
        }
    }

    private void recordCommand(
        Connection connection,
        DataAuthority.CommandEnvelope command,
        DataAuthority.CommandResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_commands (
                command_id, command_type, actor, scope, idempotency_key, deadline_at,
                fencing_token, expected_revision, payload, accepted, rejection_reason,
                result_message, result_payload, result_revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
            """)) {
            statement.setObject(1, command.commandId());
            statement.setString(2, command.type().name());
            statement.setString(3, command.actorId());
            statement.setString(4, command.scope());
            statement.setString(5, command.idempotencyKey());
            if (command.deadlineEpochMillis() > 0) {
                statement.setTimestamp(6, timestamp(command.deadlineEpochMillis()));
            } else {
                statement.setNull(6, Types.TIMESTAMP);
            }
            setNullableString(statement, 7, command.fencingToken());
            statement.setLong(8, command.expectedRevision());
            statement.setString(9, gson.toJson(command.payload()));
            statement.setBoolean(10, result.accepted());
            statement.setString(11, result.rejectionReason() == null ? null : result.rejectionReason().name());
            statement.setString(12, result.message());
            statement.setString(13, "{}");
            statement.setLong(14, result.revision());
            statement.executeUpdate();
        }
    }

    private void ensureTablesOnce(Connection connection) throws SQLException {
        if (schemaReady) {
            return;
        }
        synchronized (schemaLock) {
            if (!schemaReady) {
                ensureTables(connection);
                schemaReady = true;
            }
        }
    }

    private void ensureTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS player_profiles (
                    player_id UUID PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    normalized_username VARCHAR(16) NOT NULL,
                    first_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    online BOOLEAN NOT NULL DEFAULT FALSE,
                    current_server TEXT,
                    current_proxy TEXT,
                    total_playtime_ms BIGINT NOT NULL DEFAULT 0,
                    last_ip TEXT,
                    profile_data JSONB NOT NULL DEFAULT '{}'::jsonb,
                    revision BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS player_rank_projection (
                    player_id UUID PRIMARY KEY,
                    primary_rank TEXT NOT NULL,
                    ranks TEXT[] NOT NULL,
                    revision BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS player_rank_audit (
                    audit_id UUID PRIMARY KEY,
                    player_id UUID NOT NULL,
                    rank TEXT NOT NULL,
                    action TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    reason TEXT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS authority_commands (
                    command_id UUID PRIMARY KEY,
                    command_type TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    deadline_at TIMESTAMPTZ,
                    fencing_token TEXT,
                    expected_revision BIGINT NOT NULL DEFAULT 0,
                    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                    accepted BOOLEAN NOT NULL,
                    rejection_reason TEXT,
                    result_message TEXT,
                    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                    result_revision BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("""
                ALTER TABLE authority_commands
                ADD COLUMN IF NOT EXISTS result_revision BIGINT NOT NULL DEFAULT 0
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS player_sessions (
                    session_id UUID PRIMARY KEY,
                    player_id UUID NOT NULL,
                    proxy_id TEXT,
                    server_id TEXT,
                    state TEXT NOT NULL,
                    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    ended_at TIMESTAMPTZ,
                    disconnect_reason TEXT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS match_records (
                    match_id UUID PRIMARY KEY,
                    family_id TEXT NOT NULL,
                    map_id TEXT,
                    server_id TEXT,
                    slot_id TEXT,
                    state TEXT NOT NULL,
                    started_at TIMESTAMPTZ,
                    ended_at TIMESTAMPTZ,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS match_participant_stats (
                    match_id UUID NOT NULL REFERENCES match_records(match_id) ON DELETE CASCADE,
                    player_id UUID NOT NULL,
                    team_id TEXT,
                    placement INTEGER,
                    stats JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (match_id, player_id)
                )
                """);
        }
    }

    private UUID playerId(DataAuthority.CommandEnvelope command) {
        String value = string(command.payload(), "playerId", null);
        if (value == null || value.isBlank()) {
            String scope = command.scope();
            if (scope != null && scope.startsWith("player:")) {
                value = scope.substring("player:".length());
            }
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private UUID matchId(DataAuthority.CommandEnvelope command) {
        String value = string(command.payload(), "matchId", null);
        if (value == null || value.isBlank()) {
            String scope = command.scope();
            if (scope != null && scope.startsWith("match:")) {
                value = scope.substring("match:".length());
            }
        }
        return uuid(value);
    }

    private DataAuthority.CommandResult accepted(
        DataAuthority.CommandEnvelope command,
        String message
    ) {
        return new DataAuthority.CommandResult(command.commandId(), true, command.expectedRevision() + 1,
            DataAuthority.RejectionReason.NONE, message);
    }

    private DataAuthority.CommandResult rejected(
        DataAuthority.CommandEnvelope command,
        DataAuthority.RejectionReason reason,
        String message
    ) {
        return new DataAuthority.CommandResult(command.commandId(), false, command.expectedRevision(), reason, message);
    }

    private static String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : value.toString();
    }

    private static long longValue(Map<String, Object> payload, String key, long fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            return Long.parseLong(value.toString());
        }
        return fallback;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String objectString(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
    }

    private static Timestamp timestamp(long epochMillis) {
        return Timestamp.from(Instant.ofEpochMilli(epochMillis));
    }

    private static boolean isExpired(DataAuthority.CommandEnvelope command) {
        return command.deadlineEpochMillis() > 0L && command.deadlineEpochMillis() < System.currentTimeMillis();
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
