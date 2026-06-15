package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityEventDispatchResult;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityEventDispatchTarget;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityEventEnvelope;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityProjectionManifest;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityEventReplayResult;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityEventReplayTarget;
import sh.harold.fulcrum.api.data.impl.authority.events.PostgresAuthorityEventDispatcher;
import sh.harold.fulcrum.api.data.impl.authority.events.PostgresAuthorityProjectionReplayVerifier;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresMigrationRunner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("live-postgres")
class PostgresDataAuthorityIntegrationTest {
    private static final String EXTERNAL_JDBC_URL_PROPERTY = "fulcrum.test.postgres.jdbcUrl";
    private static final String EXTERNAL_USERNAME_PROPERTY = "fulcrum.test.postgres.username";
    private static final String EXTERNAL_PASSWORD_PROPERTY = "fulcrum.test.postgres.password";
    private static final String EXTERNAL_ALLOW_MUTATION_PROPERTY = "fulcrum.test.postgres.allowMutation";
    private static final String EXTERNAL_REQUIRE_LIVE_PROPERTY = "fulcrum.test.postgres.requireLive";
    private static final String EXTERNAL_JDBC_URL_ENV = "FULCRUM_TEST_POSTGRES_JDBC_URL";
    private static final String EXTERNAL_USERNAME_ENV = "FULCRUM_TEST_POSTGRES_USERNAME";
    private static final String EXTERNAL_PASSWORD_ENV = "FULCRUM_TEST_POSTGRES_PASSWORD";
    private static final String EXTERNAL_ALLOW_MUTATION_ENV = "FULCRUM_TEST_POSTGRES_ALLOW_MUTATION";
    private static final String EXTERNAL_REQUIRE_LIVE_ENV = "FULCRUM_TEST_POSTGRES_REQUIRE_LIVE";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static RuntimeException containerStartFailure;

    private PostgresConnectionAdapter adapter;
    private PostgresDataAuthority authority;

    @BeforeEach
    void setUp() {
        LivePostgresTarget target = requireLivePostgresTarget();
        Properties pool = new Properties();
        pool.setProperty("maximum-pool-size", "1");
        pool.setProperty("minimum-idle", "0");
        adapter = new PostgresConnectionAdapter(
            target.jdbcUrl(),
            target.username(),
            target.password(),
            "authority-test",
            pool
        );
        new PostgresMigrationRunner(adapter).runClasspathMigrations(FulcrumDataMigrations.all());
        truncateAuthorityTables();
        authority = new PostgresDataAuthority(adapter, Runnable::run, false);
        authority.validateSchema();
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    private static LivePostgresTarget requireLivePostgresTarget() {
        LivePostgresTarget external = externalPostgresTarget();
        if (external != null) {
            return external;
        }
        if (containerStartFailure != null) {
            return unavailableLivePostgres(containerStartFailure);
        }
        try {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
            return new LivePostgresTarget(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            );
        } catch (RuntimeException exception) {
            containerStartFailure = exception;
            return unavailableLivePostgres(exception);
        }
    }

    private static LivePostgresTarget externalPostgresTarget() {
        String jdbcUrl = externalSetting(EXTERNAL_JDBC_URL_PROPERTY, EXTERNAL_JDBC_URL_ENV);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String username = externalSetting(EXTERNAL_USERNAME_PROPERTY, EXTERNAL_USERNAME_ENV);
        requireOrAssumeLivePostgres(
            username != null && !username.isBlank(),
            "External PostgreSQL live proof requires "
                + EXTERNAL_USERNAME_ENV
                + " or -D"
                + EXTERNAL_USERNAME_PROPERTY
        );
        requireOrAssumeLivePostgres(
            externalMutationAllowed(),
            "External PostgreSQL live proof requires "
                + EXTERNAL_ALLOW_MUTATION_ENV
                + "=true or -D"
                + EXTERNAL_ALLOW_MUTATION_PROPERTY
                + "=true because the suite runs migrations and truncates authority tables"
        );
        String password = externalSetting(EXTERNAL_PASSWORD_PROPERTY, EXTERNAL_PASSWORD_ENV);
        return new LivePostgresTarget(jdbcUrl, username, password == null ? "" : password);
    }

    private static boolean externalMutationAllowed() {
        return Boolean.parseBoolean(externalSetting(
            EXTERNAL_ALLOW_MUTATION_PROPERTY,
            EXTERNAL_ALLOW_MUTATION_ENV
        ));
    }

    private static LivePostgresTarget unavailableLivePostgres(RuntimeException exception) {
        String message = livePostgresSkipMessage(exception);
        if (livePostgresRequired()) {
            throw new AssertionError(message, exception);
        }
        Assumptions.assumeTrue(false, message);
        throw exception;
    }

    private static void requireOrAssumeLivePostgres(boolean condition, String message) {
        if (condition) {
            return;
        }
        if (livePostgresRequired()) {
            throw new AssertionError(message);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean livePostgresRequired() {
        return Boolean.parseBoolean(externalSetting(
            EXTERNAL_REQUIRE_LIVE_PROPERTY,
            EXTERNAL_REQUIRE_LIVE_ENV
        ));
    }

    private static String externalSetting(String propertyName, String environmentName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String livePostgresSkipMessage(RuntimeException exception) {
        return "Live PostgreSQL proof requires Docker/Testcontainers or "
            + EXTERNAL_JDBC_URL_ENV
            + " with "
            + EXTERNAL_ALLOW_MUTATION_ENV
            + "=true; Testcontainers startup failed: "
            + rootMessage(exception);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Test
    void validateSchemaRejectsMissingLifecyclePolicyForAppendHeavyTable() {
        deleteLifecyclePolicy("authority_commands");
        try {
            assertThatThrownBy(authority::validateSchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing lifecycle policy")
                .hasMessageContaining("authority_commands");
        } finally {
            restoreAuthorityCommandsLifecyclePolicy();
            authority.validateSchema();
        }
    }

    @Test
    void authorityEventDispatcherAdvancesConsumerCursorAfterSuccess() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        authority.submit(rankCommand(commandId, playerId, "rank-dispatch:" + commandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        List<AuthorityEventEnvelope> dispatched = new ArrayList<>();
        String projectionVersion = "rank-projection-v1";
        String outputFingerprint = "rank-output:" + commandId;
        AuthorityProjectionManifest manifest = AuthorityProjectionManifest.of(
            "test-projection",
            projectionVersion,
            List.of("GRANT_RANK")
        );
        PostgresAuthorityEventDispatcher dispatcher = new PostgresAuthorityEventDispatcher(
            adapter,
            List.of(target("test-projection", manifest, event -> {
                dispatched.add(event);
                return AuthorityEventDispatchResult.success(projectionVersion, outputFingerprint);
            }))
        );
        dispatcher.validateSchema();

        PostgresAuthorityEventDispatcher.DispatchCycleResult result = dispatcher.dispatchOnce();

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.delivered()).isEqualTo(1);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).commandId()).isEqualTo(commandId);
        assertThat(dispatched.get(0).aggregateScope()).isEqualTo("rank:player:" + playerId);

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement cursor = connection.prepareStatement("""
                SELECT last_event_id, last_aggregate_scope, last_revision
                FROM authority_event_consumer_cursors
                WHERE consumer_name = ?
                """)) {
                cursor.setString(1, "test-projection");
                try (ResultSet rows = cursor.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("last_event_id", UUID.class)).isEqualTo(dispatched.get(0).eventId());
                    assertThat(rows.getString("last_aggregate_scope")).isEqualTo("rank:player:" + playerId);
                    assertThat(rows.getLong("last_revision")).isEqualTo(1L);
                }
            }

            try (PreparedStatement failure = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM authority_event_consumer_failures
                WHERE consumer_name = ?
                """)) {
                failure.setString(1, "test-projection");
                try (ResultSet rows = failure.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }

            try (PreparedStatement checkpoint = connection.prepareStatement("""
                SELECT event_id, aggregate_scope, revision, event_type, projection_version,
                       input_fingerprint, output_fingerprint, manifest_fingerprint, replay_batch_id
                FROM authority_projection_checkpoints
                WHERE projection_name = ?
                """)) {
                checkpoint.setString(1, "test-projection");
                try (ResultSet rows = checkpoint.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(dispatched.get(0).eventId());
                    assertThat(rows.getString("aggregate_scope")).isEqualTo("rank:player:" + playerId);
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.getString("event_type")).isEqualTo("GRANT_RANK");
                    assertThat(rows.getString("projection_version")).isEqualTo(projectionVersion);
                    assertThat(rows.getString("input_fingerprint")).isNotBlank();
                    assertThat(rows.getString("output_fingerprint")).isEqualTo(outputFingerprint);
                    assertThat(rows.getString("manifest_fingerprint")).isEqualTo(manifest.manifestFingerprint());
                    assertThat(rows.getObject("replay_batch_id", UUID.class)).isNull();
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement head = connection.prepareStatement("""
                SELECT event_id, projection_version, input_fingerprint, output_fingerprint, manifest_fingerprint
                FROM authority_projection_heads
                WHERE projection_name = ?
                """)) {
                head.setString(1, "test-projection");
                try (ResultSet rows = head.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(dispatched.get(0).eventId());
                    assertThat(rows.getString("projection_version")).isEqualTo(projectionVersion);
                    assertThat(rows.getString("input_fingerprint")).isNotBlank();
                    assertThat(rows.getString("output_fingerprint")).isEqualTo(outputFingerprint);
                    assertThat(rows.getString("manifest_fingerprint")).isEqualTo(manifest.manifestFingerprint());
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement manifestRow = connection.prepareStatement("""
                SELECT projection_version, manifest_fingerprint
                FROM authority_projection_manifests
                WHERE projection_name = ?
                """)) {
                manifestRow.setString(1, "test-projection");
                try (ResultSet rows = manifestRow.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("projection_version")).isEqualTo(projectionVersion);
                    assertThat(rows.getString("manifest_fingerprint")).isEqualTo(manifest.manifestFingerprint());
                    assertThat(rows.next()).isFalse();
                }
            }
        }

        PostgresAuthorityProjectionReplayVerifier verifier = new PostgresAuthorityProjectionReplayVerifier(adapter);
        verifier.validateSchema();
        PostgresAuthorityProjectionReplayVerifier.ReplayVerificationResult replay =
            verifier.verifyReplayTarget(
                replayTarget("test-projection", manifest, event ->
                    AuthorityEventReplayResult.success(projectionVersion, outputFingerprint)),
                PostgresAuthorityProjectionReplayVerifier.ReplayWindow.first(50),
                "integration-success"
            );
        assertThat(replay.clean()).isTrue();
        assertThat(replay.scannedEvents()).isEqualTo(1);
        assertThat(replay.verifiedEvents()).isEqualTo(1);
        assertThat(replay.missingCheckpoints()).isZero();
        assertThat(replay.mismatchedCheckpoints()).isZero();
        assertThat(replay.manifestMismatches()).isZero();
        assertThat(replay.replayFailures()).isZero();
        assertReplayEvent(
            replay.replayRunId(),
            "VERIFIED",
            "VERIFIED",
            outputFingerprint,
            outputFingerprint
        );

        PostgresAuthorityProjectionReplayVerifier.ReplayVerificationResult drift =
            verifier.verifyReplayTarget(
                replayTarget("test-projection", manifest, event ->
                    AuthorityEventReplayResult.success(projectionVersion, "rank-output:drift")),
                PostgresAuthorityProjectionReplayVerifier.ReplayWindow.first(50),
                "integration-drift"
            );
        assertThat(drift.status()).isEqualTo(PostgresAuthorityProjectionReplayVerifier.Status.MISMATCH_FOUND);
        assertThat(drift.outputMismatches()).isEqualTo(1);
        assertReplayEvent(
            drift.replayRunId(),
            "MISMATCH_FOUND",
            "OUTPUT_MISMATCH",
            outputFingerprint,
            "rank-output:drift"
        );
    }

    @Test
    void projectionReplayTreatsManifestExcludedEventsAsSkipped() throws Exception {
        UUID profileCommandId = UUID.randomUUID();
        UUID rankCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        authority.submit(profileLoginCommand(profileCommandId, playerId, "profile-skip:" + profileCommandId))
            .toCompletableFuture()
            .join();
        authority.submit(rankCommand(rankCommandId, playerId, "rank-skip:" + rankCommandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        String projectionName = "rank-only-projection";
        String projectionVersion = "rank-projection-v1";
        AuthorityProjectionManifest manifest = AuthorityProjectionManifest.of(
            projectionName,
            projectionVersion,
            List.of("GRANT_RANK")
        );
        PostgresAuthorityEventDispatcher dispatcher = new PostgresAuthorityEventDispatcher(
            adapter,
            List.of(target(projectionName, manifest, event -> AuthorityEventDispatchResult.success(
                projectionVersion,
                "rank-output:" + event.commandId()
            )))
        );

        PostgresAuthorityEventDispatcher.DispatchCycleResult dispatch = dispatcher.dispatchOnce();

        assertThat(dispatch.attempted()).isEqualTo(2);
        assertThat(dispatch.delivered()).isEqualTo(1);
        assertThat(dispatch.skipped()).isEqualTo(1);
        assertThat(dispatch.quarantined()).isZero();
        assertThat(projectionCheckpointCount(projectionName)).isEqualTo(1);

        PostgresAuthorityProjectionReplayVerifier verifier = new PostgresAuthorityProjectionReplayVerifier(adapter);
        PostgresAuthorityProjectionReplayVerifier.ReplayVerificationResult replay =
            verifier.verifyReplayTarget(
                replayTarget(projectionName, manifest, event ->
                    AuthorityEventReplayResult.success(projectionVersion, "rank-output:" + event.commandId())),
                PostgresAuthorityProjectionReplayVerifier.ReplayWindow.first(50),
                "manifest-skip"
            );

        assertThat(replay.clean()).isTrue();
        assertThat(replay.scannedEvents()).isEqualTo(2);
        assertThat(replay.verifiedEvents()).isEqualTo(1);
        assertThat(replay.skippedEvents()).isEqualTo(1);
        assertThat(replay.missingCheckpoints()).isZero();
        assertThat(replay.mismatchedCheckpoints()).isZero();
        assertThat(replay.replayFailures()).isZero();
        assertReplayRunCounts(replay.replayRunId(), 2, 1, 1);
        assertReplayVerdicts(replay.replayRunId(), "SKIPPED_BY_MANIFEST", "VERIFIED");
    }

    @Test
    void partitionEpochStoreKeepsEpochStableForOwnerAndBumpsOnOwnerChange() throws Exception {
        PostgresAuthorityPartitionEpochStore epochStore = new PostgresAuthorityPartitionEpochStore(adapter);
        epochStore.validateSchema();
        UUID playerId = UUID.randomUUID();
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + playerId
        );
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromRoute(route);

        AuthorityWriterClaim first = epochStore.claimEpoch(
            route.domain(),
            route.commandTopic(),
            custody.ownershipPartitionKey(),
            "registry-a"
        );
        AuthorityWriterClaim second = epochStore.claimEpoch(
            route.domain(),
            route.commandTopic(),
            custody.ownershipPartitionKey(),
            "registry-a"
        );
        AuthorityWriterClaim third = epochStore.claimEpoch(
            route.domain(),
            route.commandTopic(),
            custody.ownershipPartitionKey(),
            "registry-b"
        );

        assertThat(first.epoch()).isEqualTo(1L);
        assertThat(second.epoch()).isEqualTo(1L);
        assertThat(third.epoch()).isEqualTo(2L);
        assertThat(third.previousOwnerNode()).isEqualTo("registry-a");
        assertThat(third.previousEpoch()).isEqualTo(1L);
        assertThat(third.fencingToken()).startsWith("claim:v1:2:");

        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT command_topic, owner_node, epoch, last_claim_id, last_claim_fingerprint
                 FROM authority_partition_epochs
                 WHERE command_domain = ? AND partition_key = ?
            """)) {
            statement.setString(1, route.domain());
            statement.setString(2, custody.ownershipPartitionKey());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("command_topic")).isEqualTo("cmd.rank");
                assertThat(rows.getString("owner_node")).isEqualTo("registry-b");
                assertThat(rows.getLong("epoch")).isEqualTo(2L);
                assertThat(rows.getObject("last_claim_id", UUID.class)).isEqualTo(third.claimId());
                assertThat(rows.getString("last_claim_fingerprint")).isEqualTo(third.claimFingerprint());
                assertThat(rows.next()).isFalse();
            }
        }

        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT owner_node, epoch, previous_owner_node, previous_epoch, claim_fingerprint
                 FROM authority_writer_claims
                 WHERE command_domain = ? AND partition_key = ?
                 ORDER BY epoch ASC, previous_epoch ASC, claimed_at ASC
            """)) {
            statement.setString(1, route.domain());
            statement.setString(2, custody.ownershipPartitionKey());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("owner_node")).isEqualTo("registry-a");
                assertThat(rows.getLong("epoch")).isEqualTo(1L);
                assertThat(rows.getString("claim_fingerprint")).isEqualTo(first.claimFingerprint());
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("owner_node")).isEqualTo("registry-a");
                assertThat(rows.getLong("epoch")).isEqualTo(1L);
                assertThat(rows.getString("claim_fingerprint")).isEqualTo(second.claimFingerprint());
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("owner_node")).isEqualTo("registry-b");
                assertThat(rows.getLong("epoch")).isEqualTo(2L);
                assertThat(rows.getString("previous_owner_node")).isEqualTo("registry-a");
                assertThat(rows.getLong("previous_epoch")).isEqualTo(1L);
                assertThat(rows.getString("claim_fingerprint")).isEqualTo(third.claimFingerprint());
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void writerClaimReceiptRejectsSupersededOwnerBeforeNextWrite() {
        PostgresAuthorityPartitionEpochStore epochStore = new PostgresAuthorityPartitionEpochStore(adapter);
        UUID playerId = UUID.randomUUID();
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + playerId
        );
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromRoute(route);
        AuthorityWriterClaim firstClaim = epochStore.claimEpoch(
            route.domain(),
            route.commandTopic(),
            custody.ownershipPartitionKey(),
            "registry-a"
        );

        DataAuthority.CommandResult first = authority.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            "rank-claim-first:" + playerId,
            "ADMIN",
            firstClaim.fencingToken()
        )).toCompletableFuture().join();
        assertThat(first.accepted()).isTrue();

        AuthorityWriterClaim replacement = epochStore.claimEpoch(
            route.domain(),
            route.commandTopic(),
            custody.ownershipPartitionKey(),
            "registry-b"
        );
        DataAuthority.CommandResult stale = authority.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            "rank-claim-stale:" + playerId,
            "MODERATOR",
            firstClaim.fencingToken(),
            first.revision()
        )).toCompletableFuture().join();

        assertThat(replacement.epoch()).isEqualTo(2L);
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STALE_FENCING_TOKEN);
        assertThat(stale.message()).contains("older than current partition owner epoch 2");

        DataAuthority.CommandResult fresh = authority.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            "rank-claim-fresh:" + playerId,
            "MODERATOR",
            replacement.fencingToken(),
            first.revision()
        )).toCompletableFuture().join();

        assertThat(fresh.accepted()).isTrue();
        assertThat(fresh.revision()).isEqualTo(2L);
    }

    @Test
    void claimBackedModeRejectsRawNumericFencingTokens() throws Exception {
        PostgresDataAuthority strictAuthority = new PostgresDataAuthority(adapter, Runnable::run, true);
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = strictAuthority.submit(rankCommand(
            commandId,
            playerId,
            "rank-raw-fence:" + commandId,
            "ADMIN",
            "7"
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED);
        assertThat(result.message()).contains("writer claim token is required");
        try (Connection connection = adapter.getConnection();
             PreparedStatement projection = connection.prepareStatement("""
                 SELECT COUNT(*)
                 FROM player_rank_projection
                 WHERE player_id = ?
                 """)) {
            projection.setObject(1, playerId);
            try (ResultSet rows = projection.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    @Test
    void authorityEventDispatcherRetriesBeforeAdvancingCursor() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        authority.submit(rankCommand(commandId, playerId, "rank-retry:" + commandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        AtomicInteger attempts = new AtomicInteger();
        PostgresAuthorityEventDispatcher dispatcher = new PostgresAuthorityEventDispatcher(
            adapter,
            List.of(target("retry-projection", event -> attempts.incrementAndGet() == 1
                ? AuthorityEventDispatchResult.retry("temporary")
                : AuthorityEventDispatchResult.success())),
            new PostgresAuthorityEventDispatcher.Options(50, Duration.ZERO)
        );

        PostgresAuthorityEventDispatcher.DispatchCycleResult first = dispatcher.dispatchOnce();
        assertThat(first.attempted()).isEqualTo(1);
        assertThat(first.retries()).isEqualTo(1);

        try (Connection connection = adapter.getConnection();
             PreparedStatement failure = connection.prepareStatement("""
                 SELECT failure_status, failure_message, attempts
                 FROM authority_event_consumer_failures
                 WHERE consumer_name = ?
                 """)) {
            failure.setString(1, "retry-projection");
            try (ResultSet rows = failure.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("failure_status")).isEqualTo("RETRY");
                assertThat(rows.getString("failure_message")).isEqualTo("temporary");
                assertThat(rows.getInt("attempts")).isEqualTo(1);
            }
        }
        assertThat(projectionCheckpointCount("retry-projection")).isZero();

        PostgresAuthorityProjectionReplayVerifier verifier = new PostgresAuthorityProjectionReplayVerifier(adapter);
        PostgresAuthorityProjectionReplayVerifier.ReplayVerificationResult gap =
            verifier.verifyCheckpoints(
                "retry-projection",
                PostgresAuthorityProjectionReplayVerifier.ReplayWindow.first(50),
                "before-retry-success"
            );
        assertThat(gap.status()).isEqualTo(PostgresAuthorityProjectionReplayVerifier.Status.GAPS_FOUND);
        assertThat(gap.scannedEvents()).isEqualTo(1);
        assertThat(gap.missingCheckpoints()).isEqualTo(1);
        assertThat(gap.mismatchedCheckpoints()).isZero();

        PostgresAuthorityEventDispatcher.DispatchCycleResult second = dispatcher.dispatchOnce();
        assertThat(second.attempted()).isEqualTo(1);
        assertThat(second.delivered()).isEqualTo(1);

        try (Connection connection = adapter.getConnection();
             PreparedStatement failure = connection.prepareStatement("""
                 SELECT COUNT(*)
                 FROM authority_event_consumer_failures
                 WHERE consumer_name = ?
                 """)) {
            failure.setString(1, "retry-projection");
            try (ResultSet rows = failure.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThat(projectionCheckpointCount("retry-projection")).isEqualTo(1);

        PostgresAuthorityProjectionReplayVerifier.ReplayVerificationResult clean =
            verifier.verifyCheckpoints(
                "retry-projection",
                PostgresAuthorityProjectionReplayVerifier.ReplayWindow.first(50),
                "after-retry-success"
            );
        assertThat(clean.clean()).isTrue();
        assertThat(clean.verifiedEvents()).isEqualTo(1);
        assertThat(clean.missingCheckpoints()).isZero();
    }

    @Test
    void authorityEventDispatcherQuarantinesAggregateRevisionGap() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        authority.submit(rankCommand(commandId, playerId, "rank-gap:" + commandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        AtomicInteger dispatches = new AtomicInteger();
        PostgresAuthorityEventDispatcher dispatcher = new PostgresAuthorityEventDispatcher(
            adapter,
            List.of(target("gap-projection", event -> {
                dispatches.incrementAndGet();
                return AuthorityEventDispatchResult.success();
            }))
        );

        PostgresAuthorityEventDispatcher.DispatchCycleResult first = dispatcher.dispatchOnce();
        assertThat(first.delivered()).isEqualTo(1);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(projectionCheckpointCount("gap-projection")).isEqualTo(1);

        UUID gapEventId = UUID.randomUUID();
        insertRankEventGap(gapEventId, commandId, playerId, 3L);

        PostgresAuthorityEventDispatcher.DispatchCycleResult second = dispatcher.dispatchOnce();
        assertThat(second.attempted()).isEqualTo(1);
        assertThat(second.delivered()).isZero();
        assertThat(second.quarantined()).isEqualTo(1);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(projectionCheckpointCount("gap-projection")).isEqualTo(1);

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement failure = connection.prepareStatement("""
                SELECT failure_status, failure_message
                FROM authority_event_consumer_failures
                WHERE consumer_name = ? AND event_id = ?
                """)) {
                failure.setString(1, "gap-projection");
                failure.setObject(2, gapEventId);
                try (ResultSet rows = failure.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("failure_status")).isEqualTo("QUARANTINED");
                    assertThat(rows.getString("failure_message")).contains("revision 2", "revision 3");
                }
            }

            try (PreparedStatement cursor = connection.prepareStatement("""
                SELECT last_revision
                FROM authority_event_consumer_cursors
                WHERE consumer_name = ?
                """)) {
                cursor.setString(1, "gap-projection");
                try (ResultSet rows = cursor.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("last_revision")).isEqualTo(1L);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test
    void acceptedCommandWritesReceiptEventAndStateSnapshot() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = authority.submit(rankCommand(
            commandId,
            playerId,
            "rank-ledger:" + commandId,
            "ADMIN"
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(result.revision()).isEqualTo(1L);
        assertThat(result.settlement().settled()).isTrue();
        assertThat(result.settlement().commandTopic()).isEqualTo("cmd.rank");
        assertThat(result.settlement().responseTopic()).isEqualTo("rsp.rank");
        assertThat(result.settlement().stateTopic()).isEqualTo("state.rank");
        assertThat(result.settlement().partitionKey()).isEqualTo("rank:player:" + playerId);
        assertThat(result.settlement().watermark().sourceCommandId()).isEqualTo(commandId);
        DataAuthority.CommandResult replayed = authority.submit(rankCommand(
            commandId,
            playerId,
            "rank-ledger:" + commandId,
            "ADMIN"
        )).toCompletableFuture().join();
        assertThat(replayed.commandId()).isEqualTo(commandId);
        assertThat(replayed.settlement()).isEqualTo(result.settlement());

        try (Connection connection = adapter.getConnection()) {
            UUID[] commandEventId = new UUID[1];
            String[] commandEventChainHash = new String[1];
            try (PreparedStatement command = connection.prepareStatement("""
                SELECT payload_hash, command_fingerprint, provenance ->> 'authorityProvider' AS authority_provider,
                       provenance ->> 'providerKind' AS provider_kind, result_event_id, result_event_type,
                       result_payload ->> 'commandTopic' AS result_command_topic,
                       result_payload ->> 'stateTopic' AS result_state_topic,
                       result_payload ->> 'partitionKey' AS result_partition_key,
                       result_payload #>> '{watermark,sourceEventId}' AS result_source_event_id,
                       result_payload #>> '{watermark,stateFingerprint}' AS result_state_fingerprint
                FROM authority_commands
                WHERE command_id = ?
                """)) {
                command.setObject(1, commandId);
                try (ResultSet rows = command.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("payload_hash")).isNotBlank();
                    assertThat(rows.getString("command_fingerprint")).isNotBlank();
                    assertThat(rows.getString("authority_provider")).isEqualTo("postgres");
                    assertThat(rows.getString("provider_kind")).isEqualTo("postgres-local");
                    commandEventId[0] = rows.getObject("result_event_id", UUID.class);
                    assertThat(commandEventId[0]).isNotNull();
                    assertThat(rows.getString("result_event_type")).isEqualTo("GRANT_RANK");
                    assertThat(rows.getString("result_command_topic")).isEqualTo("cmd.rank");
                    assertThat(rows.getString("result_state_topic")).isEqualTo("state.rank");
                    assertThat(rows.getString("result_partition_key")).isEqualTo("rank:player:" + playerId);
                    assertThat(UUID.fromString(rows.getString("result_source_event_id"))).isEqualTo(commandEventId[0]);
                    assertThat(rows.getString("result_state_fingerprint")).isNotBlank();
                }
            }
            assertThat(result.settlement().watermark().sourceEventId()).isEqualTo(commandEventId[0]);

            try (PreparedStatement event = connection.prepareStatement("""
                SELECT event_id, aggregate_scope, aggregate_type, aggregate_id, revision, event_type,
                       hash_version, previous_chain_hash, chain_hash, payload ->> 'commandId' AS command_id,
                       provenance ->> 'authorityProvider' AS authority_provider
                FROM authority_events
                WHERE command_id = ?
                """)) {
                event.setObject(1, commandId);
                try (ResultSet rows = event.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(commandEventId[0]);
                    assertThat(rows.getString("aggregate_scope")).isEqualTo("rank:player:" + playerId);
                    assertThat(rows.getString("aggregate_type")).isEqualTo("player_rank");
                    assertThat(rows.getString("aggregate_id")).isEqualTo(playerId.toString());
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.getString("event_type")).isEqualTo("GRANT_RANK");
                    assertThat(rows.getInt("hash_version")).isEqualTo(1);
                    assertThat(rows.getString("previous_chain_hash")).isNull();
                    commandEventChainHash[0] = rows.getString("chain_hash");
                    assertThat(commandEventChainHash[0]).isNotBlank();
                    assertThat(rows.getString("command_id")).isEqualTo(commandId.toString());
                    assertThat(rows.getString("authority_provider")).isEqualTo("postgres");
                }
            }

            try (PreparedStatement snapshot = connection.prepareStatement("""
                SELECT revision, command_id, event_id, event_created_at, event_fingerprint, event_chain_hash,
                       state_fingerprint, state_payload ->> 'primaryRank' AS primary_rank
                FROM authority_state_snapshots
                WHERE aggregate_scope = ?
                """)) {
                snapshot.setString(1, "rank:player:" + playerId);
                try (ResultSet rows = snapshot.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.getObject("command_id", UUID.class)).isEqualTo(commandId);
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(commandEventId[0]);
                    assertThat(rows.getTimestamp("event_created_at")).isNotNull();
                    assertThat(rows.getString("event_fingerprint")).isNotBlank();
                    assertThat(rows.getString("event_chain_hash")).isEqualTo(commandEventChainHash[0]);
                    assertThat(rows.getString("state_fingerprint")).isNotBlank();
                    assertThat(rows.getString("primary_rank")).isEqualTo("ADMIN");
                }
            }

            try (PreparedStatement changelog = connection.prepareStatement("""
                SELECT event_id, command_id, aggregate_scope, revision, command_domain, state_topic, partition_key,
                       event_fingerprint, event_chain_hash, state_fingerprint, state_payload ->> 'primaryRank' AS primary_rank
                FROM authority_state_changelog
                WHERE aggregate_scope = ?
                """)) {
                changelog.setString(1, "rank:player:" + playerId);
                try (ResultSet rows = changelog.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(commandEventId[0]);
                    assertThat(rows.getObject("command_id", UUID.class)).isEqualTo(commandId);
                    assertThat(rows.getString("aggregate_scope")).isEqualTo("rank:player:" + playerId);
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.getString("command_domain")).isEqualTo("rank");
                    assertThat(rows.getString("state_topic")).isEqualTo("state.rank");
                    assertThat(rows.getString("partition_key")).isEqualTo("rank:player:" + playerId);
                    assertThat(rows.getString("event_fingerprint")).isNotBlank();
                    assertThat(rows.getString("event_chain_hash")).isEqualTo(commandEventChainHash[0]);
                    assertThat(rows.getString("state_fingerprint")).isNotBlank();
                    assertThat(rows.getString("primary_rank")).isEqualTo("ADMIN");
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test
    void stateRestoreDrillRebuildsMissingSnapshotFromChangelog() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String aggregateScope = "rank:player:" + playerId;
        authority.submit(rankCommand(commandId, playerId, "rank-restore:" + commandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        try (Connection connection = adapter.getConnection();
             PreparedStatement delete = connection.prepareStatement("""
                 DELETE FROM authority_state_snapshots
                 WHERE aggregate_scope = ?
                 """)) {
            delete.setString(1, aggregateScope);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }

        PostgresAuthorityStateRestoreDrill drill = new PostgresAuthorityStateRestoreDrill(adapter);
        drill.validateSchema();
        PostgresAuthorityStateRestoreDrill.RestoreRunResult missing =
            drill.verifyLatestSnapshot(aggregateScope, "integration-before-restore");
        assertThat(missing.status()).isEqualTo(PostgresAuthorityStateRestoreDrill.Status.SNAPSHOT_MISSING);
        assertThat(missing.clean()).isFalse();
        assertThat(missing.sourceRevision()).isEqualTo(1L);

        PostgresAuthorityStateRestoreDrill.RestoreRunResult restored =
            drill.restoreLatestSnapshot(aggregateScope, "integration-restore");
        assertThat(restored.status()).isEqualTo(PostgresAuthorityStateRestoreDrill.Status.RESTORED);
        assertThat(restored.clean()).isTrue();
        assertThat(restored.restored()).isTrue();
        assertThat(restored.sourceRevision()).isEqualTo(1L);
        assertThat(restored.snapshotRevision()).isEqualTo(1L);

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement snapshot = connection.prepareStatement("""
                SELECT revision, command_id, event_id, state_payload ->> 'primaryRank' AS primary_rank
                FROM authority_state_snapshots
                WHERE aggregate_scope = ?
                """)) {
                snapshot.setString(1, aggregateScope);
                try (ResultSet rows = snapshot.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.getObject("command_id", UUID.class)).isEqualTo(commandId);
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(restored.sourceEventId());
                    assertThat(rows.getString("primary_rank")).isEqualTo("ADMIN");
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement runs = connection.prepareStatement("""
                SELECT status, restored, snapshot_revision, completed_at, schema_contract_version,
                       schema_contract_fingerprint, restore_source, source_state_fingerprint,
                       snapshot_state_fingerprint, source_event_chain_hash, verification_fingerprint
                FROM authority_state_restore_runs
                WHERE aggregate_scope = ?
                ORDER BY created_at
                """)) {
                runs.setString(1, aggregateScope);
                try (ResultSet rows = runs.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("SNAPSHOT_MISSING");
                    assertThat(rows.getBoolean("restored")).isFalse();
                    assertThat(rows.getObject("snapshot_revision", Long.class)).isNull();
                    assertThat(rows.getTimestamp("completed_at")).isNotNull();
                    assertThat(rows.getInt("schema_contract_version")).isEqualTo(3);
                    assertThat(rows.getString("schema_contract_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.getString("restore_source")).isEqualTo("CHANGELOG_ONLY");
                    assertThat(rows.getString("source_state_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.getString("snapshot_state_fingerprint")).isNull();
                    assertThat(rows.getString("source_event_chain_hash")).isNotBlank();
                    assertThat(rows.getString("verification_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("RESTORED");
                    assertThat(rows.getBoolean("restored")).isTrue();
                    assertThat(rows.getObject("snapshot_revision", Long.class)).isEqualTo(1L);
                    assertThat(rows.getTimestamp("completed_at")).isNotNull();
                    assertThat(rows.getInt("schema_contract_version")).isEqualTo(3);
                    assertThat(rows.getString("schema_contract_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.getString("restore_source")).isEqualTo("CHANGELOG_RESTORED");
                    assertThat(rows.getString("source_state_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.getString("snapshot_state_fingerprint"))
                        .isEqualTo(rows.getString("source_state_fingerprint"));
                    assertThat(rows.getString("source_event_chain_hash")).isNotBlank();
                    assertThat(rows.getString("verification_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.next()).isFalse();
                }
            }
        }

        PostgresAuthorityStateRestoreDrill.RestoreRunResult verified =
            drill.verifyLatestSnapshot(aggregateScope, "integration-after-restore");
        assertThat(verified.status()).isEqualTo(PostgresAuthorityStateRestoreDrill.Status.VERIFIED);
        assertThat(verified.clean()).isTrue();
        assertThat(verified.evidence().schemaContractVersion()).isEqualTo(3);
        assertThat(verified.evidence().schemaContractFingerprint()).matches("[0-9a-f]{64}");
        assertThat(verified.evidence().restoreSource()).isEqualTo("CHANGELOG_AND_SNAPSHOT");
        assertThat(verified.verificationFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void stateRestoreDrillRefusesChangelogWhenEventLedgerDisagrees() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String aggregateScope = "rank:player:" + playerId;
        authority.submit(rankCommand(commandId, playerId, "rank-restore-poison:" + commandId, "ADMIN"))
            .toCompletableFuture()
            .join();

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("""
                 UPDATE authority_events
                 SET chain_hash = ?
                 WHERE command_id = ?
                 """)) {
                update.setString(1, "poisoned-chain-" + commandId);
                update.setObject(2, commandId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement delete = connection.prepareStatement("""
                 DELETE FROM authority_state_snapshots
                 WHERE aggregate_scope = ?
                 """)) {
                delete.setString(1, aggregateScope);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
        }

        PostgresAuthorityStateRestoreDrill drill = new PostgresAuthorityStateRestoreDrill(adapter);
        drill.validateSchema();
        PostgresAuthorityStateRestoreDrill.RestoreRunResult restored =
            drill.restoreLatestSnapshot(aggregateScope, "integration-poisoned-restore");

        assertThat(restored.status()).isEqualTo(PostgresAuthorityStateRestoreDrill.Status.FAILED);
        assertThat(restored.clean()).isFalse();
        assertThat(restored.restored()).isFalse();
        assertThat(restored.sourceRevision()).isEqualTo(1L);
        assertThat(restored.message()).contains("event.chain_hash");
        assertThat(restored.evidence().restoreSource()).isEqualTo("UNVERIFIABLE_CHANGELOG_LINEAGE");
        assertThat(restored.evidence().sourceEventChainHash()).isNotBlank();
        assertThat(restored.verificationFingerprint()).matches("[0-9a-f]{64}");

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement snapshot = connection.prepareStatement("""
                 SELECT COUNT(*)
                 FROM authority_state_snapshots
                 WHERE aggregate_scope = ?
                 """)) {
                snapshot.setString(1, aggregateScope);
                try (ResultSet rows = snapshot.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }

            try (PreparedStatement runs = connection.prepareStatement("""
                SELECT status, restored, message, restore_source, source_event_chain_hash,
                       snapshot_state_fingerprint, verification_fingerprint, completed_at
                FROM authority_state_restore_runs
                WHERE restore_run_id = ?
                """)) {
                runs.setObject(1, restored.restoreRunId());
                try (ResultSet rows = runs.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("FAILED");
                    assertThat(rows.getBoolean("restored")).isFalse();
                    assertThat(rows.getString("message")).contains("event.chain_hash");
                    assertThat(rows.getString("restore_source")).isEqualTo("UNVERIFIABLE_CHANGELOG_LINEAGE");
                    assertThat(rows.getString("source_event_chain_hash")).isNotBlank();
                    assertThat(rows.getString("snapshot_state_fingerprint")).isNull();
                    assertThat(rows.getString("verification_fingerprint")).matches("[0-9a-f]{64}");
                    assertThat(rows.getTimestamp("completed_at")).isNotNull();
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test
    void directRankCommandWithAnyRevisionIsRejectedBeforeProjectionWrite() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = authority.submit(rankCommand(
            commandId,
            playerId,
            "rank-any-revision:" + commandId,
            "ADMIN",
            "7",
            DataAuthority.ANY_REVISION
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STALE_REVISION);
        assertThat(result.message()).contains("requires a concrete expectedRevision");

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement projection = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM player_rank_projection
                WHERE player_id = ?
                """)) {
                projection.setObject(1, playerId);
                try (ResultSet rows = projection.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }

            try (PreparedStatement events = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM authority_events
                WHERE command_id = ?
                """)) {
                events.setObject(1, commandId);
                try (ResultSet rows = events.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }

            try (PreparedStatement command = connection.prepareStatement("""
                SELECT accepted, rejection_reason, result_revision
                FROM authority_commands
                WHERE command_id = ?
                """)) {
                command.setObject(1, commandId);
                try (ResultSet rows = command.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean("accepted")).isFalse();
                    assertThat(rows.getString("rejection_reason"))
                        .isEqualTo(DataAuthority.RejectionReason.STALE_REVISION.name());
                    assertThat(rows.getLong("result_revision")).isEqualTo(DataAuthority.ANY_REVISION);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test
    void missingPreviousEventChainEntryRejectsCommandAndRollsBackProjection() throws Exception {
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String aggregateScope = "rank:player:" + playerId;

        DataAuthority.CommandResult first = authority.submit(rankCommand(
            firstCommandId,
            playerId,
            "rank-chain-first:" + firstCommandId,
            "ADMIN"
        )).toCompletableFuture().join();
        assertThat(first.accepted()).isTrue();

        try (Connection connection = adapter.getConnection();
             PreparedStatement delete = connection.prepareStatement("""
                 DELETE FROM authority_events
                 WHERE aggregate_scope = ?
                 """)) {
            delete.setString(1, aggregateScope);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }

        DataAuthority.CommandResult second = authority.submit(rankCommand(
            secondCommandId,
            playerId,
            "rank-chain-second:" + secondCommandId,
            "MODERATOR",
            "7",
            first.revision()
        )).toCompletableFuture().join();

        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);
        assertThat(second.message()).contains("Missing authority event chain predecessor");

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement rank = connection.prepareStatement("""
                SELECT primary_rank, revision
                FROM player_rank_projection
                WHERE player_id = ?
                """)) {
                rank.setObject(1, playerId);
                try (ResultSet rows = rank.executeQuery()) {
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement aggregate = connection.prepareStatement("""
                SELECT revision
                FROM authority_aggregate_versions
                WHERE scope = ?
                """)) {
                aggregate.setString(1, aggregateScope);
                try (ResultSet rows = aggregate.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("revision")).isEqualTo(1L);
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement events = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM authority_events
                WHERE aggregate_scope = ?
                """)) {
                events.setString(1, aggregateScope);
                try (ResultSet rows = events.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void quotedRankReadUsesStateReceiptWhenHotProjectionIsMissing() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = authority.submit(rankCommand(
            commandId,
            playerId,
            "rank-read-receipt:" + commandId,
            "ADMIN"
        )).toCompletableFuture().join();
        assertThat(result.accepted()).isTrue();

        try (Connection connection = adapter.getConnection();
             PreparedStatement deleteProjection = connection.prepareStatement("""
                 DELETE FROM player_rank_projection
                 WHERE player_id = ?
                 """)) {
            deleteProjection.setObject(1, playerId);
            deleteProjection.executeUpdate();
        }

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = authority
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(result.revision()))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(read.quote().observedRevision()).isEqualTo(result.revision());
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().sourceEventId())
            .isEqualTo(result.settlement().watermark().sourceEventId());
        assertThat(read.quote().deliveryReceipt().satisfies(
            "player_rank",
            "rank:player:" + playerId,
            result.revision()
        )).isTrue();
    }

    @Test
    void quotedProfileReadUsesStateReceiptWhenProjectionIsMissing() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = authority.submit(profileLoginCommand(
            commandId,
            playerId,
            "profile-read-receipt:" + commandId
        )).toCompletableFuture().join();
        assertThat(result.accepted()).isTrue();

        try (Connection connection = adapter.getConnection();
             PreparedStatement deleteProjection = connection.prepareStatement("""
                 DELETE FROM player_profiles
                 WHERE player_id = ?
                 """)) {
            deleteProjection.setObject(1, playerId);
            deleteProjection.executeUpdate();
        }

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read = authority
            .quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(result.revision()))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(read.quote().observedRevision()).isEqualTo(result.revision());
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().sourceEventId())
            .isEqualTo(result.settlement().watermark().sourceEventId());
        assertThat(read.quote().deliveryReceipt().satisfies(
            "player_profile",
            "player:" + playerId,
            result.revision()
        )).isTrue();
    }

    @Test
    void loggedCommandPortRecordsIngressAndTerminalOutcome() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PostgresLoggedAuthorityCommandPort commandPort = new PostgresLoggedAuthorityCommandPort(adapter, authority);
        commandPort.validateSchema();

        DataAuthority.CommandResult result = commandPort.submit(rankCommand(
            commandId,
            playerId,
            "rank-ingress:" + commandId,
            "ADMIN"
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();

        try (Connection connection = adapter.getConnection();
             PreparedStatement ingress = connection.prepareStatement("""
                SELECT command_type, aggregate_scope, status, accepted, rejection_reason,
                       payload_hash, command_fingerprint, manifest_payload, command_payload,
                       writer_lane_count, writer_lane, writer_lane_key_fingerprint, writer_lane_fencing_scope,
                       replay_eligibility,
                       guard_evidence ->> 'phase' AS guard_phase,
                       guard_evidence #>> '{contract,expectedFingerprint}' AS guard_contract,
                       guard_evidence #>> '{result,accepted}' AS guard_result_accepted,
                       guard_evidence::text AS guard_evidence_text,
                       guard_evidence_fingerprint,
                       result_payload ->> 'stateTopic' AS result_state_topic,
                       result_payload ->> 'partitionKey' AS result_partition_key,
                       result_payload ->> 'settled' AS result_settled,
                       result_payload #>> '{watermark,sourceEventId}' AS result_source_event_id,
                       completed_at
                 FROM authority_command_ingress_log
                 WHERE command_id = ?
                 """)) {
            ingress.setObject(1, commandId);
            try (ResultSet rows = ingress.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("command_type")).isEqualTo("GRANT_RANK");
                assertThat(rows.getString("aggregate_scope")).isEqualTo("rank:player:" + playerId);
                assertThat(rows.getString("status")).isEqualTo("APPLIED");
                assertThat(rows.getBoolean("accepted")).isTrue();
                assertThat(rows.getString("rejection_reason")).isEqualTo("NONE");
                assertThat(rows.getString("replay_eligibility")).isEqualTo("NOT_REPLAYABLE");
                assertThat(rows.getString("guard_phase")).isEqualTo("TERMINAL");
                assertThat(rows.getString("guard_contract"))
                    .isEqualTo(DataAuthorityCommandContracts.fingerprint());
                assertThat(rows.getString("guard_result_accepted")).isEqualTo("true");
                assertThat(rows.getString("guard_evidence_text"))
                    .contains("schemaContract", "routeManifest", "deadline", "principal", "routeAndScope",
                        "writerLane", "terminalOutcome");
                assertThat(rows.getString("guard_evidence_fingerprint")).matches("[0-9a-f]{64}");
                AuthorityCommandLane expectedLane = AuthorityCommandLane.fromRoute(
                    AuthorityCommandRoute.from(DataAuthority.CommandType.GRANT_RANK, "rank:player:" + playerId),
                    AuthorityCommandLane.DEFAULT_LANE_COUNT
                );
                assertThat(rows.getInt("writer_lane_count")).isEqualTo(expectedLane.laneCount());
                assertThat(rows.getInt("writer_lane")).isEqualTo(expectedLane.lane());
                assertThat(rows.getString("writer_lane_key_fingerprint"))
                    .isEqualTo(expectedLane.laneKeyFingerprint());
                assertThat(rows.getString("writer_lane_fencing_scope")).isEqualTo(expectedLane.fencingScope());
                assertThat(rows.getString("result_state_topic")).isEqualTo("state.rank");
                assertThat(rows.getString("result_partition_key")).isEqualTo("rank:player:" + playerId);
                assertThat(rows.getString("result_settled")).isEqualTo("true");
                assertThat(UUID.fromString(rows.getString("result_source_event_id")))
                    .isEqualTo(result.settlement().watermark().sourceEventId());
                assertThat(rows.getString("payload_hash")).isNotBlank();
                assertThat(rows.getString("command_fingerprint")).isNotBlank();
                assertThat(rows.getString("manifest_payload"))
                    .contains("\"declarationId\": \"GRANT_RANK\"")
                    .doesNotContain("\"commandType\"");
                assertThat(rows.getString("command_payload")).contains("\"primaryRank\": \"ADMIN\"");
                assertThat(rows.getTimestamp("completed_at")).isNotNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void loggedFencedCommandRecordsWriterClaimReceipt() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        PostgresAuthorityPartitionEpochStore epochStore = new PostgresAuthorityPartitionEpochStore(adapter);
        DataAuthority.CommandPort fencedAuthority = new AuthorityFencingCommandPort(
            authority,
            epochStore,
            "registry-a"
        );
        PostgresLoggedAuthorityCommandPort commandPort = new PostgresLoggedAuthorityCommandPort(
            adapter,
            fencedAuthority
        );
        PostgresAuthorityCommandIngressLog ingressLog = new PostgresAuthorityCommandIngressLog(adapter);
        commandPort.validateSchema();
        ingressLog.validateSchema();
        epochStore.validateSchema();

        DataAuthority.CommandResult result = commandPort.submit(rankCommand(
            commandId,
            playerId,
            "rank-ingress-claim:" + commandId,
            "ADMIN",
            ""
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        AuthorityWriterClaimToken claim = AuthorityWriterClaimToken.parse(result.settlement().fencingToken());
        assertThat(claim).isNotNull();

        PostgresAuthorityCommandIngressLog.CommandIngressEntry entry = ingressLog.find(commandId).orElseThrow();
        assertThat(entry.writerClaimEpoch()).isEqualTo(claim.epoch());
        assertThat(entry.writerClaimId()).isEqualTo(claim.claimId());
        assertThat(entry.writerClaimFingerprint()).isEqualTo(claim.claimFingerprint());
        assertThat(entry.settlement().fencingToken()).isEqualTo(result.settlement().fencingToken());

        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT writer_claim_epoch,
                        writer_claim_id,
                        writer_claim_fingerprint,
                        result_payload ->> 'fencingToken' AS result_fencing_token,
                        guard_evidence #>> '{writerClaim,claimBacked}' AS guard_claim_backed,
                        guard_evidence #>> '{writerClaim,epoch}' AS guard_claim_epoch,
                        guard_evidence #>> '{writerClaim,claimId}' AS guard_claim_id,
                        guard_evidence #>> '{writerClaim,claimFingerprint}' AS guard_claim_fingerprint,
                        guard_evidence::text AS guard_evidence_text
                 FROM authority_command_ingress_log
                 WHERE command_id = ?
                 """)) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("writer_claim_epoch")).isEqualTo(claim.epoch());
                assertThat(rows.getObject("writer_claim_id", UUID.class)).isEqualTo(claim.claimId());
                assertThat(rows.getString("writer_claim_fingerprint")).isEqualTo(claim.claimFingerprint());
                assertThat(rows.getString("result_fencing_token")).isEqualTo(result.settlement().fencingToken());
                assertThat(rows.getString("guard_claim_backed")).isEqualTo("true");
                assertThat(rows.getString("guard_claim_epoch")).isEqualTo(Long.toString(claim.epoch()));
                assertThat(rows.getString("guard_claim_id")).isEqualTo(claim.claimId().toString());
                assertThat(rows.getString("guard_claim_fingerprint")).isEqualTo(claim.claimFingerprint());
                assertThat(rows.getString("guard_evidence_text"))
                    .contains("writerClaim", "writerClaimReceipt");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void commandRefusalLogRecordsPreSubmitRejectionEvidence() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID scopedPlayerId = UUID.randomUUID();
        UUID payloadPlayerId = UUID.randomUUID();
        PostgresAuthorityCommandRefusalLog refusalLog = new PostgresAuthorityCommandRefusalLog(adapter);
        refusalLog.validateSchema();

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("commandId", commandId.toString());
        wire.put("declarationId", "GRANT_RANK");
        wire.put("actorId", "rank-service");
        wire.put("scope", "rank:player:" + scopedPlayerId);
        wire.put("idempotencyKey", "rank-refusal:" + commandId);
        wire.put("deadlineEpochMillis", System.currentTimeMillis() + 1000);
        wire.put("fencingToken", "3");
        wire.put("expectedRevision", DataAuthority.ANY_REVISION);
        wire.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        wire.put("contractFingerprint", DataAuthorityCommandContracts.fingerprint());
        wire.put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        wire.put("route", AuthorityCommandRoute.from(DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + scopedPlayerId).payload());
        wire.put("payload", Map.of(
            "playerId", payloadPlayerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN")
        ));

        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            commandId,
            false,
            DataAuthority.ANY_REVISION,
            DataAuthority.RejectionReason.INVALID_SCOPE,
            "scope does not match payload aggregate id"
        );

        refusalLog.recordMessageBusRefusal("paper-1", "registry-1", wire, result);

        try (Connection connection = adapter.getConnection();
             PreparedStatement refusals = connection.prepareStatement("""
                SELECT command_type, aggregate_scope, idempotency_key, claimed_actor,
                       verified_principal, origin_node, authority_route, provider_kind,
                       contract_version, expected_contract_fingerprint, received_contract_fingerprint,
                       rejection_reason, result_revision, result_message, replay_eligibility,
                       manifest_payload ->> 'targetNode' AS target_node,
                       command_payload ->> 'playerId' AS payload_player_id,
                       result_payload ->> 'settled' AS result_settled,
                       guard_evidence ->> 'phase' AS guard_phase,
                       guard_evidence #>> '{contract,receivedFingerprint}' AS guard_received_contract,
                       guard_evidence #>> '{result,rejectionReason}' AS guard_rejection_reason,
                       guard_evidence::text AS guard_evidence_text,
                       guard_evidence_fingerprint,
                       payload_hash, refusal_fingerprint
                FROM authority_command_refusal_log
                WHERE command_id = ?
                """)) {
            refusals.setObject(1, commandId);
            try (ResultSet rows = refusals.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("command_type")).isEqualTo("GRANT_RANK");
                assertThat(rows.getString("aggregate_scope")).isEqualTo("rank:player:" + scopedPlayerId);
                assertThat(rows.getString("idempotency_key")).isEqualTo("rank-refusal:" + commandId);
                assertThat(rows.getString("claimed_actor")).isEqualTo("rank-service");
                assertThat(rows.getString("verified_principal")).isEqualTo("node:paper-1");
                assertThat(rows.getString("origin_node")).isEqualTo("paper-1");
                assertThat(rows.getString("authority_route")).isEqualTo("messagebus:paper-1->registry-1");
                assertThat(rows.getString("provider_kind")).isEqualTo("message-bus-provider");
                assertThat(rows.getInt("contract_version")).isEqualTo(DataAuthority.COMMAND_SCHEMA_VERSION);
                assertThat(rows.getString("expected_contract_fingerprint"))
                    .isEqualTo(DataAuthorityCommandContracts.fingerprint());
                assertThat(rows.getString("received_contract_fingerprint"))
                    .isEqualTo(DataAuthorityCommandContracts.fingerprint());
                assertThat(rows.getString("rejection_reason")).isEqualTo("INVALID_SCOPE");
                assertThat(rows.getLong("result_revision")).isEqualTo(DataAuthority.ANY_REVISION);
                assertThat(rows.getString("result_message")).contains("scope does not match");
                assertThat(rows.getString("replay_eligibility")).isEqualTo("NOT_REPLAYABLE");
                assertThat(rows.getString("target_node")).isEqualTo("registry-1");
                assertThat(rows.getString("payload_player_id")).isEqualTo(payloadPlayerId.toString());
                assertThat(rows.getString("result_settled")).isEqualTo("false");
                assertThat(rows.getString("guard_phase")).isEqualTo("PRE_SUBMIT_REFUSAL");
                assertThat(rows.getString("guard_received_contract"))
                    .isEqualTo(DataAuthorityCommandContracts.fingerprint());
                assertThat(rows.getString("guard_rejection_reason")).isEqualTo("INVALID_SCOPE");
                assertThat(rows.getString("guard_evidence_text"))
                    .contains("schemaContract", "routeManifest", "principal", "routeAndScope", "terminalOutcome");
                assertThat(rows.getString("guard_evidence_fingerprint")).matches("[0-9a-f]{64}");
                assertThat(rows.getString("payload_hash")).matches("[0-9a-f]{64}");
                assertThat(rows.getString("refusal_fingerprint")).matches("[0-9a-f]{64}");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void commandIngressLogDoesNotReplayTerminalDomainRejection() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = rankCommand(
            commandId,
            playerId,
            "rank-rejected:" + commandId,
            "ADMIN"
        );
        DataAuthority.CommandPort rejectingPort = ignored -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                commandId,
                false,
                0L,
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "test rejection"
            )
        );
        PostgresLoggedAuthorityCommandPort commandPort = new PostgresLoggedAuthorityCommandPort(adapter, rejectingPort);
        PostgresAuthorityCommandIngressLog ingressLog = new PostgresAuthorityCommandIngressLog(adapter);

        DataAuthority.CommandResult rejected = commandPort.submit(command).toCompletableFuture().join();

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED);

        PostgresAuthorityCommandIngressLog.CommandIngressEntry entry = ingressLog.find(commandId).orElseThrow();
        assertThat(entry.status()).isEqualTo(PostgresAuthorityCommandIngressLog.CommandIngressStatus.REJECTED);
        assertThat(entry.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.NOT_REPLAYABLE);
        assertThat(entry.replayable()).isFalse();
        assertThat(entry.settlement().settled()).isFalse();
        assertThat(ingressLog.findReplayCandidates(10))
            .extracting(PostgresAuthorityCommandIngressLog.CommandIngressEntry::commandId)
            .doesNotContain(commandId);

        PostgresAuthorityCommandIngressLog.ReplayResult replay = ingressLog.replay(commandId, rejectingPort)
            .toCompletableFuture()
            .join();

        assertThat(replay.submitted()).isFalse();
        assertThat(replay.commandResult()).isNull();
    }

    @Test
    void commandIngressLogReplaysFailedCommandFrame() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = rankCommand(
            commandId,
            playerId,
            "rank-replay:" + commandId,
            "ADMIN"
        );
        DataAuthority.CommandPort failingPort = ignored ->
            CompletableFuture.failedFuture(new IllegalStateException("authority unavailable"));
        PostgresLoggedAuthorityCommandPort firstAttempt = new PostgresLoggedAuthorityCommandPort(adapter, failingPort);
        PostgresAuthorityCommandIngressLog ingressLog = new PostgresAuthorityCommandIngressLog(adapter);

        DataAuthority.CommandResult failed = firstAttempt.submit(command).toCompletableFuture().join();

        assertThat(failed.accepted()).isFalse();
        assertThat(failed.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);
        PostgresAuthorityCommandIngressLog.CommandIngressEntry failedEntry = ingressLog.find(commandId).orElseThrow();
        assertThat(failedEntry.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.REPLAYABLE);
        assertThat(failedEntry.replayable()).isTrue();
        assertThat(failedEntry.settlement().settled()).isFalse();
        assertThat(ingressLog.findReplayCandidates(10))
            .extracting(PostgresAuthorityCommandIngressLog.CommandIngressEntry::commandId)
            .contains(commandId);

        PostgresLoggedAuthorityCommandPort retryingPort = new PostgresLoggedAuthorityCommandPort(adapter, authority);
        PostgresAuthorityCommandIngressLog.ReplayResult replay = ingressLog.replay(commandId, retryingPort)
            .toCompletableFuture()
            .join();

        assertThat(replay.submitted()).isTrue();
        assertThat(replay.commandResult().accepted()).isTrue();
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> replayedRead = authority
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(replay.commandResult().revision()))
            .toCompletableFuture()
            .join();
        assertThat(replayedRead.satisfied()).isFalse();
        assertThat(replayedRead.snapshot()).isEmpty();
        assertThat(replayedRead.quote().observedRevision()).isEqualTo(replay.commandResult().revision());
        assertThat(replayedRead.quote().deliveryReceipt()).isNotNull();
        assertThat(replayedRead.quote().deliveryReceipt().satisfies(
            "player_rank",
            "rank:player:" + playerId,
            replay.commandResult().revision()
        )).isTrue();

        PostgresAuthorityCommandIngressLog.CommandIngressEntry replayed = ingressLog.find(commandId).orElseThrow();
        assertThat(replayed.status()).isEqualTo(PostgresAuthorityCommandIngressLog.CommandIngressStatus.APPLIED);
        assertThat(replayed.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.NOT_REPLAYABLE);
        assertThat(replayed.settlement().settled()).isTrue();
        assertThat(replayed.settlement().stateTopic()).isEqualTo("state.rank");
        assertThat(replayed.settlement().partitionKey()).isEqualTo("rank:player:" + playerId);
        assertThat(replayed.replayAttempts()).isEqualTo(1);
        assertThat(replayed.lastReplayedAt()).isNotNull();
    }

    @Test
    void commandIngressLogQuarantinesReplayRouteMismatch() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = rankCommand(
            commandId,
            playerId,
            "rank-replay-quarantine:" + commandId,
            "ADMIN"
        );
        DataAuthority.CommandPort failingPort = ignored ->
            CompletableFuture.failedFuture(new IllegalStateException("authority unavailable"));
        PostgresLoggedAuthorityCommandPort firstAttempt = new PostgresLoggedAuthorityCommandPort(adapter, failingPort);
        PostgresAuthorityCommandIngressLog ingressLog = new PostgresAuthorityCommandIngressLog(adapter);

        DataAuthority.CommandResult failed = firstAttempt.submit(command).toCompletableFuture().join();

        assertThat(failed.accepted()).isFalse();
        assertThat(failed.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);

        try (Connection connection = adapter.getConnection();
             PreparedStatement tamper = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET partition_key = ?
                 WHERE command_id = ?
                 """)) {
            tamper.setString(1, "rank:player:" + UUID.randomUUID());
            tamper.setObject(2, commandId);
            assertThat(tamper.executeUpdate()).isEqualTo(1);
        }

        PostgresAuthorityCommandIngressLog.CommandIngressEntry tampered = ingressLog.find(commandId).orElseThrow();
        assertThat(tampered.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.REPLAYABLE);
        assertThat(tampered.replayable()).isTrue();
        assertThat(tampered.routeMatchesCommand()).isFalse();
        assertThat(tampered.laneMatchesCommand()).isFalse();
        assertThat(ingressLog.findReplayCandidates(10))
            .extracting(PostgresAuthorityCommandIngressLog.CommandIngressEntry::commandId)
            .contains(commandId);

        PostgresAuthorityCommandIngressLog.ReplayResult replay = ingressLog.replay(commandId, authority)
            .toCompletableFuture()
            .join();

        assertThat(replay.submitted()).isFalse();
        assertThat(replay.commandResult()).isNull();
        assertThat(replay.message()).contains("route no longer matches");

        PostgresAuthorityCommandIngressLog.CommandIngressEntry quarantined = ingressLog.find(commandId).orElseThrow();
        assertThat(quarantined.status()).isEqualTo(PostgresAuthorityCommandIngressLog.CommandIngressStatus.QUARANTINED);
        assertThat(quarantined.accepted()).isFalse();
        assertThat(quarantined.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED);
        assertThat(quarantined.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.NOT_REPLAYABLE);
        assertThat(quarantined.replayable()).isFalse();
        assertThat(quarantined.failureMessage()).contains("route no longer matches");
        assertThat(quarantined.resultMessage()).contains("route no longer matches");
        assertThat(quarantined.guardEvidence()).containsEntry("phase", "REPLAY_QUARANTINE");
        assertThat(quarantined.guardEvidenceFingerprint()).matches("[0-9a-f]{64}");

        Object replayQuarantine = quarantined.guardEvidence().get("replayQuarantine");
        assertThat(replayQuarantine).isInstanceOf(Map.class);
        Map<?, ?> replayEvidence = (Map<?, ?>) replayQuarantine;
        assertThat(replayEvidence.get("status")).isEqualTo("QUARANTINED");
        assertThat(replayEvidence.get("replayEligibility")).isEqualTo("NOT_REPLAYABLE");
        assertThat(replayEvidence.get("commandFingerprint")).isEqualTo(quarantined.commandFingerprint());
        assertThat(replayEvidence.get("expectedRoute").toString()).contains("rank:player:" + playerId);
        assertThat(replayEvidence.get("storedRoute").toString()).contains(quarantined.partitionKey());
        assertThat(replayEvidence.get("expectedWriterLane").toString()).contains("fencingScope");
        assertThat(replayEvidence.get("storedWriterLane").toString()).contains("laneKeyFingerprint");
        assertThat(ingressLog.findReplayCandidates(10))
            .extracting(PostgresAuthorityCommandIngressLog.CommandIngressEntry::commandId)
            .doesNotContain(commandId);
    }

    @Test
    void commandIngressLogQuarantinesReplayTopologyMismatch() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = rankCommand(
            commandId,
            playerId,
            "rank-replay-topology-quarantine:" + commandId,
            "ADMIN"
        );
        DataAuthority.CommandPort failingPort = ignored ->
            CompletableFuture.failedFuture(new IllegalStateException("authority unavailable"));
        PostgresLoggedAuthorityCommandPort firstAttempt = new PostgresLoggedAuthorityCommandPort(adapter, failingPort);
        PostgresAuthorityCommandIngressLog ingressLog = new PostgresAuthorityCommandIngressLog(adapter);

        DataAuthority.CommandResult failed = firstAttempt.submit(command).toCompletableFuture().join();

        assertThat(failed.accepted()).isFalse();
        assertThat(failed.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);

        try (Connection connection = adapter.getConnection();
             PreparedStatement tamper = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET guard_evidence = jsonb_set(
                     guard_evidence,
                     '{topology,authorityDomainTopologyFingerprint}',
                     to_jsonb(?::text),
                     true
                 )
                 WHERE command_id = ?
                 """)) {
            tamper.setString(1, "0000000000000000000000000000000000000000000000000000000000000000");
            tamper.setObject(2, commandId);
            assertThat(tamper.executeUpdate()).isEqualTo(1);
        }

        PostgresAuthorityCommandIngressLog.CommandIngressEntry tampered = ingressLog.find(commandId).orElseThrow();
        assertThat(tampered.replayable()).isTrue();
        assertThat(tampered.routeMatchesCommand()).isTrue();
        assertThat(tampered.laneMatchesCommand()).isTrue();

        PostgresAuthorityCommandIngressLog.ReplayResult replay = ingressLog.replay(commandId, authority)
            .toCompletableFuture()
            .join();

        assertThat(replay.submitted()).isFalse();
        assertThat(replay.commandResult()).isNull();
        assertThat(replay.message()).contains("authorityDomainTopologyFingerprint");

        PostgresAuthorityCommandIngressLog.CommandIngressEntry quarantined = ingressLog.find(commandId).orElseThrow();
        assertThat(quarantined.status()).isEqualTo(PostgresAuthorityCommandIngressLog.CommandIngressStatus.QUARANTINED);
        assertThat(quarantined.replayEligibility())
            .isEqualTo(PostgresAuthorityCommandIngressLog.ReplayEligibility.NOT_REPLAYABLE);
        assertThat(quarantined.failureMessage()).contains("authorityDomainTopologyFingerprint");
        Object replayQuarantine = quarantined.guardEvidence().get("replayQuarantine");
        assertThat(replayQuarantine).isInstanceOf(Map.class);
        Map<?, ?> replayEvidence = (Map<?, ?>) replayQuarantine;
        assertThat(replayEvidence.get("expectedTopology").toString())
            .contains(AuthorityDomainTopology.fingerprint());
        assertThat(replayEvidence.get("storedTopology").toString())
            .contains("0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadIsRejectedAndRecorded() throws Exception {
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String idempotencyKey = "rank-conflict:" + firstCommandId;

        DataAuthority.CommandResult first = authority.submit(rankCommand(
            firstCommandId,
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();

        DataAuthority.CommandResult second = authority.submit(rankCommand(
            secondCommandId,
            playerId,
            idempotencyKey,
            "MODERATOR"
        )).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second.commandId()).isEqualTo(secondCommandId);
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT);

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement conflict = connection.prepareStatement("""
                SELECT original_command_id, attempted_command_id, expected_fingerprint, actual_fingerprint
                FROM authority_idempotency_conflicts
                WHERE idempotency_key = ?
                """)) {
                conflict.setString(1, idempotencyKey);
                try (ResultSet rows = conflict.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("original_command_id", UUID.class)).isEqualTo(firstCommandId);
                    assertThat(rows.getObject("attempted_command_id", UUID.class)).isEqualTo(secondCommandId);
                    assertThat(rows.getString("expected_fingerprint")).isNotBlank();
                    assertThat(rows.getString("actual_fingerprint")).isNotBlank();
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement event = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM authority_events
                WHERE command_id = ?
                """)) {
                event.setObject(1, secondCommandId);
                try (ResultSet rows = event.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void reusedIdempotencyKeyWithNewCommandIdIsRejectedAndRecorded() throws Exception {
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String idempotencyKey = "rank-command-id-conflict:" + firstCommandId;

        DataAuthority.CommandResult first = authority.submit(rankCommand(
            firstCommandId,
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();

        DataAuthority.CommandResult second = authority.submit(rankCommand(
            secondCommandId,
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second.commandId()).isEqualTo(secondCommandId);
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT);

        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement conflict = connection.prepareStatement("""
                SELECT original_command_id, attempted_command_id, expected_fingerprint, actual_fingerprint
                FROM authority_idempotency_conflicts
                WHERE idempotency_key = ?
                """)) {
                conflict.setString(1, idempotencyKey);
                try (ResultSet rows = conflict.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("original_command_id", UUID.class)).isEqualTo(firstCommandId);
                    assertThat(rows.getObject("attempted_command_id", UUID.class)).isEqualTo(secondCommandId);
                    assertThat(rows.getString("expected_fingerprint"))
                        .isNotEqualTo(rows.getString("actual_fingerprint"));
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        String idempotencyKey,
        String rank
    ) {
        return rankCommand(commandId, playerId, idempotencyKey, rank, "7", 0L);
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        String idempotencyKey,
        String rank,
        String fencingToken
    ) {
        return rankCommand(commandId, playerId, idempotencyKey, rank, fencingToken, 0L);
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        String idempotencyKey,
        String rank,
        String fencingToken,
        long expectedRevision
    ) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                idempotencyKey,
                System.currentTimeMillis() + 60_000L,
                fencingToken,
                expectedRevision
            ),
            playerId,
            rank,
            List.of("DEFAULT", rank)
        );
    }

    private static DataAuthority.PlayerProfileCommand profileLoginCommand(
        UUID commandId,
        UUID playerId,
        String idempotencyKey
    ) {
        long now = System.currentTimeMillis();
        return new DataAuthority.PlayerProfileCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
                "profile-service",
                "player:" + playerId,
                idempotencyKey,
                now + 60_000L,
                "7",
                DataAuthority.ANY_REVISION
            ),
            playerId,
            "ReplayUser",
            now,
            "lobby-1",
            "proxy-1",
            "127.0.0.1",
            "world",
            "0,64,0",
            "SURVIVAL",
            1,
            0.5F,
            20.0D,
            20,
            "lastProxySession"
        );
    }

    private void truncateAuthorityTables() {
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 TRUNCATE TABLE
                     authority_projection_replay_run_events,
                     authority_projection_replay_runs,
                     authority_projection_manifests,
                     authority_projection_heads,
                     authority_projection_checkpoints,
                     authority_event_consumer_failures,
                     authority_event_consumer_cursors,
                     authority_state_restore_runs,
                     authority_state_changelog,
                     authority_state_snapshots,
                     authority_events,
                     authority_idempotency_conflicts,
                     authority_command_refusal_log,
                     authority_command_ingress_log,
                     authority_commands,
                     authority_writer_claims,
                     authority_partition_epochs,
                     authority_aggregate_versions,
                     player_rank_audit,
                     player_rank_projection,
                     player_profiles,
                     player_sessions,
                     match_participant_stats,
                     match_records
                 RESTART IDENTITY CASCADE
                 """)) {
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to reset authority integration test tables", exception);
        }
    }

    private void deleteLifecyclePolicy(String tableName) {
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 DELETE FROM authority_lifecycle_policies
                 WHERE table_name = ?
                 """)) {
            statement.setString(1, tableName);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to delete lifecycle policy for " + tableName, exception);
        }
    }

    private void restoreAuthorityCommandsLifecyclePolicy() {
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_lifecycle_policies (
                     table_name,
                     lifecycle_timestamp_column,
                     lifecycle_class,
                     partition_strategy,
                     partition_interval,
                     retention_days,
                     archive_before_delete,
                     protect_incomplete_statuses,
                     retention_owner,
                     notes
                 ) VALUES (
                     'authority_commands',
                     'created_at',
                     'APPEND_AUDIT',
                     'MONTHLY_RANGE',
                     'P1M',
                     90,
                     TRUE,
                     ARRAY[]::text[],
                     'authority',
                     'Command audit and durable idempotency backstop.'
                 )
                 ON CONFLICT (table_name) DO UPDATE SET
                     lifecycle_timestamp_column = EXCLUDED.lifecycle_timestamp_column,
                     lifecycle_class = EXCLUDED.lifecycle_class,
                     partition_strategy = EXCLUDED.partition_strategy,
                     partition_interval = EXCLUDED.partition_interval,
                     retention_days = EXCLUDED.retention_days,
                     archive_before_delete = EXCLUDED.archive_before_delete,
                     protect_incomplete_statuses = EXCLUDED.protect_incomplete_statuses,
                     retention_owner = EXCLUDED.retention_owner,
                     notes = EXCLUDED.notes,
                     updated_at = CURRENT_TIMESTAMP
                 """)) {
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to restore authority_commands lifecycle policy", exception);
        }
    }

    private void insertRankEventGap(UUID eventId, UUID commandId, UUID playerId, long revision) {
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_events (
                     event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                     revision, event_type, payload, provenance, created_at,
                     command_domain, event_topic, partition_key
                 ) VALUES (?, ?, ?, 'player_rank', ?, ?, 'GRANT_RANK',
                     '{}'::jsonb, '{}'::jsonb,
                     (SELECT COALESCE(MAX(last_event_created_at), CURRENT_TIMESTAMP)
                      FROM authority_event_consumer_cursors) + INTERVAL '1 second',
                     'rank', 'evt.rank', ?)
                 """)) {
            statement.setObject(1, eventId);
            statement.setObject(2, commandId);
            statement.setString(3, "rank:player:" + playerId);
            statement.setString(4, playerId.toString());
            statement.setLong(5, revision);
            statement.setString(6, "rank:player:" + playerId);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to insert authority event gap", exception);
        }
    }

    private int projectionCheckpointCount(String projectionName) {
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT COUNT(*)
                 FROM authority_projection_checkpoints
                 WHERE projection_name = ?
                 """)) {
            statement.setString(1, projectionName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to count projection checkpoints", exception);
        }
    }

    private void assertReplayEvent(
        UUID replayRunId,
        String expectedRunStatus,
        String expectedVerdict,
        String expectedOutputFingerprint,
        String actualOutputFingerprint
    ) {
        try (Connection connection = adapter.getConnection()) {
            try (PreparedStatement run = connection.prepareStatement("""
                SELECT status, scanned_events, completed_at
                FROM authority_projection_replay_runs
                WHERE replay_run_id = ?
                """)) {
                run.setObject(1, replayRunId);
                try (ResultSet rows = run.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo(expectedRunStatus);
                    assertThat(rows.getInt("scanned_events")).isEqualTo(1);
                    assertThat(rows.getTimestamp("completed_at")).isNotNull();
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement event = connection.prepareStatement("""
                SELECT verdict, expected_output_fingerprint, actual_output_fingerprint
                FROM authority_projection_replay_run_events
                WHERE replay_run_id = ?
                """)) {
                event.setObject(1, replayRunId);
                try (ResultSet rows = event.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("verdict")).isEqualTo(expectedVerdict);
                    assertThat(rows.getString("expected_output_fingerprint")).isEqualTo(expectedOutputFingerprint);
                    assertThat(rows.getString("actual_output_fingerprint")).isEqualTo(actualOutputFingerprint);
                    assertThat(rows.next()).isFalse();
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to assert replay event", exception);
        }
    }

    private void assertReplayRunCounts(UUID replayRunId, int scannedEvents, int verifiedEvents, int skippedEvents) {
        try (Connection connection = adapter.getConnection();
             PreparedStatement run = connection.prepareStatement("""
                 SELECT scanned_events, verified_events, skipped_events
                 FROM authority_projection_replay_runs
                 WHERE replay_run_id = ?
                 """)) {
            run.setObject(1, replayRunId);
            try (ResultSet rows = run.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("scanned_events")).isEqualTo(scannedEvents);
                assertThat(rows.getInt("verified_events")).isEqualTo(verifiedEvents);
                assertThat(rows.getInt("skipped_events")).isEqualTo(skippedEvents);
                assertThat(rows.next()).isFalse();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to assert replay run counts", exception);
        }
    }

    private void assertReplayVerdicts(UUID replayRunId, String... expectedVerdicts) {
        List<String> verdicts = new ArrayList<>();
        try (Connection connection = adapter.getConnection();
             PreparedStatement event = connection.prepareStatement("""
                 SELECT verdict
                 FROM authority_projection_replay_run_events
                 WHERE replay_run_id = ?
                 ORDER BY event_created_at, event_id
                 """)) {
            event.setObject(1, replayRunId);
            try (ResultSet rows = event.executeQuery()) {
                while (rows.next()) {
                    verdicts.add(rows.getString("verdict"));
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to assert replay verdicts", exception);
        }
        assertThat(verdicts).containsExactly(expectedVerdicts);
    }

    private static AuthorityEventDispatchTarget target(
        String consumerName,
        ThrowingDispatcher dispatcher
    ) {
        return new AuthorityEventDispatchTarget() {
            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) throws Exception {
                return dispatcher.dispatch(event);
            }
        };
    }

    private static AuthorityEventDispatchTarget target(
        String consumerName,
        AuthorityProjectionManifest manifest,
        ThrowingDispatcher dispatcher
    ) {
        return new AuthorityEventDispatchTarget() {
            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public AuthorityProjectionManifest projectionManifest() {
                return manifest;
            }

            @Override
            public AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) throws Exception {
                return dispatcher.dispatch(event);
            }
        };
    }

    private static AuthorityEventReplayTarget replayTarget(
        String projectionName,
        ThrowingReplayer replayer
    ) {
        return new AuthorityEventReplayTarget() {
            @Override
            public String projectionName() {
                return projectionName;
            }

            @Override
            public AuthorityEventReplayResult replay(AuthorityEventEnvelope event) throws Exception {
                return replayer.replay(event);
            }
        };
    }

    private static AuthorityEventReplayTarget replayTarget(
        String projectionName,
        AuthorityProjectionManifest manifest,
        ThrowingReplayer replayer
    ) {
        return new AuthorityEventReplayTarget() {
            @Override
            public String projectionName() {
                return projectionName;
            }

            @Override
            public AuthorityProjectionManifest projectionManifest() {
                return manifest;
            }

            @Override
            public AuthorityEventReplayResult replay(AuthorityEventEnvelope event) throws Exception {
                return replayer.replay(event);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingDispatcher {
        AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingReplayer {
        AuthorityEventReplayResult replay(AuthorityEventEnvelope event) throws Exception;
    }

    private record LivePostgresTarget(String jdbcUrl, String username, String password) {
        private LivePostgresTarget {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl is required");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username is required");
            }
            password = password == null ? "" : password;
        }
    }
}
