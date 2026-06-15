package sh.harold.fulcrum.api.data.impl.authority.events;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-substrate")
class CassandraAuthorityHotStateProjectionLiveTest {
    private static final String KEYSPACE = "fulcrum_authority";

    @Test
    void liveCassandraAppliesSchemaRestoresRankAndServesQuotedHotRead() {
        try (CassandraContainer<?> cassandra = startCassandra()) {
            try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .build()) {
                session.execute("""
                    CREATE KEYSPACE IF NOT EXISTS fulcrum_authority
                    WITH replication = {'class':'SimpleStrategy','replication_factor':1}
                    """);
                CassandraAuthorityHotStateSchema.apply(session, KEYSPACE);

                CassandraAuthorityHotStateProjection projection =
                    new CassandraAuthorityHotStateProjection(session, KEYSPACE);
                projection.validateSchema();

                UUID playerId = UUID.randomUUID();
                AuthorityEventDispatchResult dispatch = projection.dispatch(rankEvent(playerId, 7L));
                DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = projection
                    .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(7L))
                    .toCompletableFuture()
                    .join();

                assertThat(dispatch.successful()).isTrue();
                assertThat(read.satisfied()).isTrue();
                assertThat(read.snapshot()).get().satisfies(snapshot -> {
                    assertThat(snapshot.playerId()).isEqualTo(playerId);
                    assertThat(snapshot.primaryRank()).isEqualTo("ADMIN");
                    assertThat(snapshot.revision()).isEqualTo(7L);
                });
                assertThat(read.quote().provenance().sourceTier())
                    .isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
                assertThat(read.quote().deliveryReceipt().sourceProvider())
                    .isEqualTo(CassandraAuthorityHotStateProjection.PROJECTION_NAME);
            }
        }
    }

    private static CassandraContainer<?> startCassandra() {
        CassandraContainer<?> container = new CassandraContainer<>("cassandra:4.1.4");
        try {
            container.start();
            return container;
        } catch (RuntimeException exception) {
            unavailableLiveSubstrate("Cassandra", exception);
            throw exception;
        }
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

    private static void unavailableLiveSubstrate(String substrate, RuntimeException exception) {
        String message = "Live " + substrate + " proof requires Docker/Testcontainers; startup failed: "
            + exception.getMessage();
        if (liveSubstratesRequired()) {
            throw new IllegalStateException(message, exception);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean liveSubstratesRequired() {
        return Boolean.getBoolean("fulcrum.test.substrates.requireLive")
            || Boolean.parseBoolean(System.getenv().getOrDefault(
                "FULCRUM_TEST_SUBSTRATES_REQUIRE_LIVE",
                "false"
            ));
    }
}
