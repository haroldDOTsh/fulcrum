package sh.harold.fulcrum.api.data.impl.authority.events;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CassandraAuthorityHotStateProjectionTest {
    @Test
    void declaresQueryOwnedTablesAndProjectionMetadata() {
        CassandraAuthorityHotStateProjection projection = new CassandraAuthorityHotStateProjection(
            mock(CqlSession.class),
            "fulcrum_authority"
        );

        assertThat(projection.tables())
            .containsExactly(
                new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                    CassandraAuthorityHotStateProjection.PROFILE_TABLE,
                    "player_id"
                ),
                new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                    CassandraAuthorityHotStateProjection.RANK_TABLE,
                    "player_id"
                ),
                new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                    CassandraAuthorityHotStateProjection.MATCH_TABLE,
                    "match_id"
                )
            );
    }

    @Test
    void schemaResourceOwnsDeclaredProjectionTablesAndTtlPolicy() throws Exception {
        String schema = resource(CassandraAuthorityHotStateProjection.SCHEMA_RESOURCE);

        assertThat(schema)
            .contains("CREATE TABLE IF NOT EXISTS " + CassandraAuthorityHotStateProjection.PROFILE_TABLE)
            .contains("CREATE TABLE IF NOT EXISTS " + CassandraAuthorityHotStateProjection.RANK_TABLE)
            .contains("CREATE TABLE IF NOT EXISTS " + CassandraAuthorityHotStateProjection.MATCH_TABLE)
            .contains("WITH default_time_to_live = 900")
            .contains("WITH default_time_to_live = 86400")
            .contains("LeveledCompactionStrategy");
    }

    @Test
    void schemaApplicatorQualifiesDeclaredTablesWithConfiguredKeyspace() {
        List<SimpleStatement> statements = CassandraAuthorityHotStateSchema.statements("fulcrum_authority");

        assertThat(statements).hasSize(3);
        assertThat(statements)
            .extracting(SimpleStatement::getQuery)
            .allSatisfy(query -> assertThat(query).doesNotContain("--"));
        assertThat(statements.get(0).getQuery())
            .contains("CREATE TABLE IF NOT EXISTS fulcrum_authority."
                + CassandraAuthorityHotStateProjection.PROFILE_TABLE);
        assertThat(statements.get(1).getQuery())
            .contains("CREATE TABLE IF NOT EXISTS fulcrum_authority."
                + CassandraAuthorityHotStateProjection.RANK_TABLE);
        assertThat(statements.get(2).getQuery())
            .contains("CREATE TABLE IF NOT EXISTS fulcrum_authority."
                + CassandraAuthorityHotStateProjection.MATCH_TABLE);
    }

    @Test
    void schemaApplicatorExecutesCanonicalStatements() {
        CqlSession session = mock(CqlSession.class);

        CassandraAuthorityHotStateSchema.apply(session, "fulcrum_authority");

        org.mockito.ArgumentCaptor<SimpleStatement> statement =
            org.mockito.ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session, times(3)).execute(statement.capture());
        assertThat(statement.getAllValues())
            .extracting(SimpleStatement::getQuery)
            .allSatisfy(query -> assertThat(query).contains("fulcrum_authority."));
    }

    @Test
    void schemaApplicatorRejectsInvalidKeyspaceIdentifier() {
        assertThatThrownBy(() -> CassandraAuthorityHotStateSchema.statements("bad-keyspace"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyspace must be a Cassandra identifier");
    }

    @Test
    void manifestDeclaresProfileAndRankEventTypes() {
        CassandraAuthorityHotStateProjection projection = new CassandraAuthorityHotStateProjection(
            mock(CqlSession.class),
            "fulcrum_authority"
        );

        assertThat(projection.consumerName()).isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_NAME);
        AuthorityStateRestoreTarget restoreTarget = projection;
        assertThat(restoreTarget.projectionName()).isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_NAME);
        assertThat(restoreTarget.projectionVersion()).isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_VERSION);
        assertThat(projection.projectionManifest().acceptedEventTypes())
            .contains("RECORD_PLAYER_LOGIN", "END_SESSION", "GRANT_RANK", "REVOKE_RANK", "RECORD_MATCH_START");
    }

    @Test
    void dispatchRankEventWritesReducedStateRecord() {
        CqlSession session = mock(CqlSession.class);
        ResultSet missing = empty();
        ResultSet inserted = result(true);
        when(session.execute(any(SimpleStatement.class))).thenReturn(missing, inserted);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");
        UUID playerId = UUID.randomUUID();

        AuthorityEventDispatchResult result = projection.dispatch(rankEvent(playerId, 7L));

        assertThat(result.successful()).isTrue();
        assertThat(result.projectionVersion()).isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_VERSION);
        org.mockito.ArgumentCaptor<SimpleStatement> statement =
            org.mockito.ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session, times(2)).execute(statement.capture());
        assertThat(statement.getAllValues().get(0).getQuery())
            .contains("SELECT player_id")
            .contains(CassandraAuthorityHotStateProjection.RANK_TABLE);
        assertThat(statement.getAllValues().get(1).getQuery())
            .contains("INSERT INTO fulcrum_authority.authority_player_ranks_by_player");
        assertThat(statement.getAllValues().get(1).getPositionalValues())
            .contains("rank:player:" + playerId, "state.rank");
        assertThat(statement.getAllValues().get(1).getPositionalValues())
            .anySatisfy(value -> assertThat(value.toString()).contains("\"primaryRank\":\"ADMIN\""));
    }

    @Test
    void dispatchMatchEventWritesReducedStateRecord() {
        CqlSession session = mock(CqlSession.class);
        ResultSet missing = empty();
        ResultSet inserted = result(true);
        when(session.execute(any(SimpleStatement.class))).thenReturn(missing, inserted);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");
        UUID matchId = UUID.randomUUID();

        AuthorityEventDispatchResult result = projection.dispatch(matchEvent(matchId, 2L));

        assertThat(result.successful()).isTrue();
        org.mockito.ArgumentCaptor<SimpleStatement> statement =
            org.mockito.ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session, times(2)).execute(statement.capture());
        assertThat(statement.getAllValues().get(0).getQuery())
            .contains("SELECT match_id")
            .contains(CassandraAuthorityHotStateProjection.MATCH_TABLE);
        assertThat(statement.getAllValues().get(1).getQuery())
            .contains("INSERT INTO fulcrum_authority.authority_live_matches_by_match")
            .contains("match_id");
        assertThat(statement.getAllValues().get(1).getPositionalValues())
            .contains("match:" + matchId, "state.match");
        assertThat(statement.getAllValues().get(1).getPositionalValues())
            .anySatisfy(value -> assertThat(value.toString()).contains("\"state\":\"STARTED\""));
    }

    @Test
    void restoreRankWritesInsertIfAbsentForNewProjectionRow() {
        CqlSession session = mock(CqlSession.class);
        ResultSet inserted = result(true);
        when(session.execute(any(SimpleStatement.class))).thenReturn(inserted);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");
        AuthorityStateRecord record = rankRecord(4L);

        AuthorityStateRestoreResult result = projection.restore(record);

        assertThat(result.restored()).isTrue();
        org.mockito.ArgumentCaptor<SimpleStatement> statement =
            org.mockito.ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session).execute(statement.capture());
        assertThat(statement.getValue().getQuery())
            .contains("INSERT INTO fulcrum_authority.authority_player_ranks_by_player")
            .contains("IF NOT EXISTS");
        assertThat(statement.getValue().getPositionalValues())
            .contains(record.aggregateScope(), record.revision(), record.stateFingerprint());
    }

    @Test
    void restoreRankUsesRevisionGuardWhenProjectionRowAlreadyExists() {
        CqlSession session = mock(CqlSession.class);
        ResultSet existing = result(false);
        ResultSet updated = result(true);
        when(session.execute(any(SimpleStatement.class))).thenReturn(existing, updated);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");

        AuthorityStateRestoreResult result = projection.restore(rankRecord(5L));

        assertThat(result.restored()).isTrue();
        org.mockito.ArgumentCaptor<SimpleStatement> statement =
            org.mockito.ArgumentCaptor.forClass(SimpleStatement.class);
        verify(session, times(2)).execute(statement.capture());
        assertThat(statement.getAllValues().get(1).getQuery())
            .contains("UPDATE fulcrum_authority.authority_player_ranks_by_player")
            .contains("IF revision < ?");
    }

    @Test
    void restoreSkipsStaleProjectionRecordWhenRevisionGuardRejectsUpdate() {
        CqlSession session = mock(CqlSession.class);
        ResultSet existing = result(false);
        ResultSet stale = result(false);
        when(session.execute(any(SimpleStatement.class))).thenReturn(existing, stale);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");

        AuthorityStateRestoreResult result = projection.restore(rankRecord(3L));

        assertThat(result.restored()).isFalse();
        assertThat(result.message()).isEqualTo("existing projection is newer or equal");
    }

    @Test
    void quotedRankReadReportsHotStateProvenance() {
        CqlSession session = mock(CqlSession.class);
        UUID playerId = UUID.randomUUID();
        ResultSet ranks = rowResult(rankRow(playerId, 4L));
        when(session.execute(any(SimpleStatement.class))).thenReturn(ranks);
        CassandraAuthorityHotStateProjection projection =
            new CassandraAuthorityHotStateProjection(session, "fulcrum_authority");

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = projection
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().sourceProvider())
            .isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_NAME);
    }

    private static String resource(String path) throws Exception {
        try (var input = CassandraAuthorityHotStateProjectionTest.class.getClassLoader()
            .getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ResultSet empty() {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.one()).thenReturn(null);
        return resultSet;
    }

    private static ResultSet rowResult(Row row) {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.one()).thenReturn(row);
        return resultSet;
    }

    private static Row rankRow(UUID playerId, long revision) {
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Row row = mock(Row.class);
        when(row.getUuid("player_id")).thenReturn(playerId);
        when(row.getString("state_payload")).thenReturn("""
            {"playerId":"%s","primaryRank":"ADMIN","ranks":["DEFAULT","ADMIN"],"revision":%d}
            """.formatted(playerId, revision));
        when(row.getString("aggregate_scope")).thenReturn("rank:player:" + playerId);
        when(row.getString("aggregate_type")).thenReturn("player_rank");
        when(row.getString("aggregate_id")).thenReturn(playerId.toString());
        when(row.getString("command_domain")).thenReturn("rank");
        when(row.getString("state_topic")).thenReturn("state.rank");
        when(row.getString("partition_key")).thenReturn("rank:player:" + playerId);
        when(row.getUuid("source_command_id")).thenReturn(commandId);
        when(row.getUuid("source_event_id")).thenReturn(eventId);
        when(row.getLong("revision")).thenReturn(revision);
        when(row.getInstant("event_created_at")).thenReturn(Instant.ofEpochMilli(1_000L));
        when(row.getInt("source_partition")).thenReturn(2);
        when(row.getLong("source_offset")).thenReturn(40L);
        when(row.getString("state_fingerprint")).thenReturn("state-fingerprint");
        when(row.getString("event_chain_hash")).thenReturn("event-chain-hash");
        return row;
    }

    private static ResultSet result(boolean applied) {
        Row row = mock(Row.class);
        when(row.getBoolean("[applied]")).thenReturn(applied);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.one()).thenReturn(row);
        return resultSet;
    }

    private static AuthorityEventEnvelope rankEvent(UUID playerId, long revision) {
        return new AuthorityEventEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            revision,
            "GRANT_RANK",
            Map.of(
                "route", Map.of(
                    "domain", "rank",
                    "stateTopic", "state.rank",
                    "partitionKey", "rank:player:" + playerId
                ),
                "payload", Map.of(
                    "playerId", playerId.toString(),
                    "primaryRank", "ADMIN",
                    "ranks", List.of("DEFAULT", "ADMIN")
                )
            ),
            Map.of("actorId", "rank-service"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            Instant.now()
        );
    }

    private static AuthorityEventEnvelope matchEvent(UUID matchId, long revision) {
        return new AuthorityEventEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "match:" + matchId,
            "match",
            matchId.toString(),
            revision,
            "RECORD_MATCH_START",
            Map.of(
                "route", Map.of(
                    "domain", "match",
                    "stateTopic", "state.match",
                    "partitionKey", "match:" + matchId
                ),
                "payload", Map.of(
                    "matchId", matchId.toString(),
                    "familyId", "duels",
                    "mapId", "arena-1",
                    "serverId", "server-1",
                    "slotId", "slot-1",
                    "state", "STARTED",
                    "startedAt", 1234L,
                    "slotMetadata", Map.of("variant", "standard"),
                    "participants", List.of(Map.of("playerId", UUID.randomUUID().toString()))
                )
            ),
            Map.of("actorId", "match-service"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            Instant.now()
        );
    }

    private static AuthorityStateRecord rankRecord(long revision) {
        UUID playerId = UUID.randomUUID();
        Map<String, Object> statePayload = Map.of(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN"),
            "revision", revision
        );
        return new AuthorityStateRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            revision,
            "rank",
            "state.rank",
            "rank:player:" + playerId,
            statePayload,
            AuthorityStateRecord.stateFingerprint(statePayload),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            Instant.now()
        );
    }
}
