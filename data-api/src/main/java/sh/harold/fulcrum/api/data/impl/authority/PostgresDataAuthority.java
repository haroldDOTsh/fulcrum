package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * PostgreSQL-backed authority implementation for Fulcrum's durable data plane.
 */
public final class PostgresDataAuthority implements DataAuthority.CommandPort,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PlayerRankReader {

    private static final int AUTHORITY_EVENT_CHAIN_HASH_VERSION = 1;
    private static final String REGISTRY_SERVICE_ID = "registry-service";

    private static final List<String> REQUIRED_TABLES = List.of(
        "authority_commands",
        "player_profiles",
        "player_sessions",
        "player_rank_projection",
        "player_rank_audit",
        "authority_aggregate_versions",
        "authority_events",
        "authority_command_ingress_log",
        "authority_event_consumer_failures",
        "authority_projection_checkpoints",
        "authority_projection_replay_runs",
        "authority_projection_replay_run_events",
        "authority_state_snapshots",
        "authority_state_changelog",
        "authority_state_restore_runs",
        "authority_idempotency_conflicts",
        "match_records",
        "match_participant_stats",
        "analytics_events",
        "authority_lifecycle_policies",
        "authority_partition_epochs",
        "authority_writer_claims"
    );

    private static final Map<String, LifecyclePolicyRequirement> REQUIRED_LIFECYCLE_POLICIES = Map.ofEntries(
        Map.entry("authority_commands", monthly("created_at", "APPEND_AUDIT", 90)),
        Map.entry("authority_command_ingress_log", monthly("received_at", "APPEND_AUDIT", 90)),
        Map.entry("authority_events", monthly("created_at", "APPEND_EVENT", 90)),
        Map.entry("authority_event_consumer_failures", monthly("created_at", "APPEND_OPERATION", 90)),
        Map.entry("authority_projection_checkpoints", monthly("event_created_at", "APPEND_OPERATION", 180)),
        Map.entry("authority_projection_replay_runs", monthly("created_at", "APPEND_OPERATION", 180)),
        Map.entry("authority_projection_replay_run_events", monthly("event_created_at", "APPEND_OPERATION", 180)),
        Map.entry("authority_state_changelog",
            new LifecyclePolicyRequirement("event_created_at", "RESTORE_SOURCE", "COMPACTED_CHANGELOG", 3650)),
        Map.entry("authority_state_restore_runs", monthly("created_at", "APPEND_OPERATION", 180)),
        Map.entry("authority_idempotency_conflicts", monthly("created_at", "APPEND_AUDIT", 90)),
        Map.entry("authority_writer_claims", monthly("claimed_at", "APPEND_AUDIT", 90)),
        Map.entry("player_rank_audit", monthly("created_at", "APPEND_AUDIT", 365)),
        Map.entry("player_sessions", monthly("started_at", "APPEND_AUDIT", 90)),
        Map.entry("match_records", monthly("created_at", "APPEND_AUDIT", 180)),
        Map.entry("match_participant_stats", monthly("created_at", "APPEND_AUDIT", 180)),
        Map.entry("analytics_events", monthly("created_at", "APPEND_ANALYTICS", 90))
    );

    private final PostgresConnectionAdapter connectionAdapter;
    private final Executor executor;
    private final Gson gson = new Gson();

    public PostgresDataAuthority(PostgresConnectionAdapter connectionAdapter) {
        this(connectionAdapter, ForkJoinPool.commonPool());
    }

    public PostgresDataAuthority(PostgresConnectionAdapter connectionAdapter, Executor executor) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    public void validateSchema() {
        validateSchemaContract(FulcrumSchemaContract.loadDefault());
        try (Connection connection = connectionAdapter.getConnection()) {
            validateRequiredTables(connection);
            validateLifecyclePolicies(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate Postgres authority schema", exception);
        }
    }

    static void validateSchemaContract(FulcrumSchemaContract contract) {
        Objects.requireNonNull(contract, "contract");
        for (String table : REQUIRED_TABLES) {
            contract.requireDataApiOwnedTable(table, REGISTRY_SERVICE_ID);
        }
    }

    private void validateRequiredTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required authority table '" + table + "'. Run data-api migrations before startup."
                        );
                    }
                }
            }
        }
    }

    private void validateLifecyclePolicies(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT lifecycle_timestamp_column, lifecycle_class, partition_strategy, retention_days
            FROM authority_lifecycle_policies
            WHERE table_name = ?
            """)) {
            for (Map.Entry<String, LifecyclePolicyRequirement> entry : REQUIRED_LIFECYCLE_POLICIES.entrySet()) {
                String table = entry.getKey();
                LifecyclePolicyRequirement expected = entry.getValue();
                statement.setString(1, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                            "Missing lifecycle policy for append-heavy authority table '" + table
                                + "'. Run data-api migrations before startup."
                        );
                    }
                    String actualTimestampColumn = resultSet.getString("lifecycle_timestamp_column");
                    String actualLifecycleClass = resultSet.getString("lifecycle_class");
                    String actualPartitionStrategy = resultSet.getString("partition_strategy");
                    int actualRetentionDays = resultSet.getInt("retention_days");
                    if (!expected.timestampColumn().equals(actualTimestampColumn)
                        || !expected.lifecycleClass().equals(actualLifecycleClass)
                        || !expected.partitionStrategy().equals(actualPartitionStrategy)
                        || actualRetentionDays < expected.minRetentionDays()) {
                        throw new IllegalStateException(
                            "Invalid lifecycle policy for append-heavy authority table '" + table
                                + "': expected timestampColumn=" + expected.timestampColumn()
                                + ", lifecycleClass=" + expected.lifecycleClass()
                                + ", partitionStrategy=" + expected.partitionStrategy()
                                + ", minRetentionDays=" + expected.minRetentionDays()
                        );
                    }
                    if (resultSet.next()) {
                        throw new IllegalStateException(
                            "Duplicate lifecycle policy rows for append-heavy authority table '" + table + "'"
                        );
                    }
                }
            }
        }
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        return CompletableFuture.supplyAsync(() -> execute(command), executor);
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.player_id, p.username, p.normalized_username, p.online, p.current_server,
                            p.current_proxy, p.total_playtime_ms, p.profile_data::text AS profile_data,
                            p.revision,
                            s.aggregate_scope AS watermark_aggregate_scope,
                            s.aggregate_type AS watermark_aggregate_type,
                            s.aggregate_id AS watermark_aggregate_id,
                            s.command_domain AS watermark_command_domain,
                            s.state_topic AS watermark_state_topic,
                            s.partition_key AS watermark_partition_key,
                            s.command_id AS watermark_command_id,
                            s.event_id AS watermark_event_id,
                            s.revision AS watermark_revision,
                            s.event_created_at AS watermark_event_created_at,
                            s.state_fingerprint AS watermark_state_fingerprint,
                            s.event_chain_hash AS watermark_event_chain_hash
                     FROM player_profiles p
                     LEFT JOIN authority_state_snapshots s
                         ON s.aggregate_scope = ('player:' || p.player_id::text)
                     WHERE p.player_id = ?
                     """)) {
                statement.setObject(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    long revision = resultSet.getLong("revision");
                    return Optional.of(new DataAuthority.PlayerProfileSnapshot(
                        resultSet.getObject("player_id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getString("normalized_username"),
                        resultSet.getBoolean("online"),
                        resultSet.getString("current_server"),
                        resultSet.getString("current_proxy"),
                        resultSet.getLong("total_playtime_ms"),
                        jsonMap(resultSet.getString("profile_data")),
                        revision,
                        snapshotWatermark(
                            resultSet,
                            "player:" + playerId,
                            "player_profile",
                            playerId.toString(),
                            revision
                        )
                    ));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to read player profile " + playerId, exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            requirement
        );
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.player_id AS profile_player_id, p.username, p.normalized_username, p.online,
                            p.current_server, p.current_proxy, p.total_playtime_ms,
                            p.profile_data::text AS profile_data, p.revision AS profile_revision,
                            s.aggregate_scope AS watermark_aggregate_scope,
                            s.aggregate_type AS watermark_aggregate_type,
                            s.aggregate_id AS watermark_aggregate_id,
                            s.command_domain AS watermark_command_domain,
                            s.state_topic AS watermark_state_topic,
                            s.partition_key AS watermark_partition_key,
                            s.command_id AS watermark_command_id,
                            s.event_id AS watermark_event_id,
                            s.revision AS watermark_revision,
                            s.event_created_at AS watermark_event_created_at,
                            s.state_fingerprint AS watermark_state_fingerprint,
                            s.event_chain_hash AS watermark_event_chain_hash
                     FROM (SELECT ?::uuid AS player_id) target
                     LEFT JOIN player_profiles p
                         ON p.player_id = target.player_id
                     LEFT JOIN authority_state_snapshots s
                         ON s.aggregate_scope = ('player:' || target.player_id::text)
                     """)) {
                statement.setObject(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return quoteProfileSnapshot(
                            playerId,
                            effectiveRequirement,
                            Optional.empty(),
                            null,
                            System.currentTimeMillis()
                        );
                    }
                    UUID profilePlayerId = resultSet.getObject("profile_player_id", UUID.class);
                    long profileRevision = resultSet.getLong("profile_revision");
                    if (resultSet.wasNull()) {
                        profileRevision = 0L;
                    }
                    DataAuthority.SnapshotWatermark watermark = snapshotWatermark(
                        resultSet,
                        "player:" + playerId,
                        "player_profile",
                        playerId.toString(),
                        profileRevision
                    );
                    DataAuthority.ProjectionDeliveryReceipt deliveryReceipt =
                        DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_profile", watermark);
                    Optional<DataAuthority.PlayerProfileSnapshot> snapshot = Optional.empty();
                    if (profilePlayerId != null) {
                        snapshot = Optional.of(new DataAuthority.PlayerProfileSnapshot(
                            profilePlayerId,
                            resultSet.getString("username"),
                            resultSet.getString("normalized_username"),
                            resultSet.getBoolean("online"),
                            resultSet.getString("current_server"),
                            resultSet.getString("current_proxy"),
                            resultSet.getLong("total_playtime_ms"),
                            jsonMap(resultSet.getString("profile_data")),
                            profileRevision,
                            watermark
                        ));
                    }
                    return quoteProfileSnapshot(
                        playerId,
                        effectiveRequirement,
                        snapshot,
                        deliveryReceipt,
                        System.currentTimeMillis()
                    );
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to quote player profile " + playerId, exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.player_id, r.primary_rank, r.ranks, r.revision,
                            s.aggregate_scope AS watermark_aggregate_scope,
                            s.aggregate_type AS watermark_aggregate_type,
                            s.aggregate_id AS watermark_aggregate_id,
                            s.command_domain AS watermark_command_domain,
                            s.state_topic AS watermark_state_topic,
                            s.partition_key AS watermark_partition_key,
                            s.command_id AS watermark_command_id,
                            s.event_id AS watermark_event_id,
                            s.revision AS watermark_revision,
                            s.event_created_at AS watermark_event_created_at,
                            s.state_fingerprint AS watermark_state_fingerprint,
                            s.event_chain_hash AS watermark_event_chain_hash
                     FROM player_rank_projection r
                     LEFT JOIN authority_state_snapshots s
                         ON s.aggregate_scope = ('rank:player:' || r.player_id::text)
                     WHERE r.player_id = ?
                     """)) {
                statement.setObject(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    long revision = resultSet.getLong("revision");
                    return Optional.of(new DataAuthority.PlayerRankSnapshot(
                        resultSet.getObject("player_id", UUID.class),
                        resultSet.getString("primary_rank"),
                        arrayToStrings(resultSet.getArray("ranks")),
                        revision,
                        snapshotWatermark(
                            resultSet,
                            "rank:player:" + playerId,
                            "player_rank",
                            playerId.toString(),
                            revision
                        )
                    ));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to read player ranks " + playerId, exception);
            }
        }, executor);
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            requirement
        );
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.player_id AS rank_player_id, r.primary_rank, r.ranks, r.revision AS rank_revision,
                            s.aggregate_scope AS watermark_aggregate_scope,
                            s.aggregate_type AS watermark_aggregate_type,
                            s.aggregate_id AS watermark_aggregate_id,
                            s.command_domain AS watermark_command_domain,
                            s.state_topic AS watermark_state_topic,
                            s.partition_key AS watermark_partition_key,
                            s.command_id AS watermark_command_id,
                            s.event_id AS watermark_event_id,
                            s.revision AS watermark_revision,
                            s.event_created_at AS watermark_event_created_at,
                            s.state_fingerprint AS watermark_state_fingerprint,
                            s.event_chain_hash AS watermark_event_chain_hash
                     FROM (SELECT ?::uuid AS player_id) target
                     LEFT JOIN player_rank_projection r
                         ON r.player_id = target.player_id
                     LEFT JOIN authority_state_snapshots s
                         ON s.aggregate_scope = ('rank:player:' || target.player_id::text)
                     """)) {
                statement.setObject(1, playerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return quoteRankSnapshot(
                            playerId,
                            effectiveRequirement,
                            Optional.empty(),
                            null,
                            System.currentTimeMillis()
                        );
                    }
                    UUID rankPlayerId = resultSet.getObject("rank_player_id", UUID.class);
                    long rankRevision = resultSet.getLong("rank_revision");
                    if (resultSet.wasNull()) {
                        rankRevision = 0L;
                    }
                    DataAuthority.SnapshotWatermark watermark = snapshotWatermark(
                        resultSet,
                        "rank:player:" + playerId,
                        "player_rank",
                        playerId.toString(),
                        rankRevision
                    );
                    DataAuthority.ProjectionDeliveryReceipt deliveryReceipt =
                        DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_rank", watermark);
                    Optional<DataAuthority.PlayerRankSnapshot> snapshot = Optional.empty();
                    if (rankPlayerId != null) {
                        snapshot = Optional.of(new DataAuthority.PlayerRankSnapshot(
                            rankPlayerId,
                            resultSet.getString("primary_rank"),
                            arrayToStrings(resultSet.getArray("ranks")),
                            rankRevision,
                            watermark
                        ));
                    }
                    return quoteRankSnapshot(
                        playerId,
                        effectiveRequirement,
                        snapshot,
                        deliveryReceipt,
                        System.currentTimeMillis()
                    );
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to quote player ranks " + playerId, exception);
            }
        }, executor);
    }

    private static DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quoteProfileSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerProfileSnapshot> snapshot,
        DataAuthority.ProjectionDeliveryReceipt deliveryReceipt,
        long nowEpochMillis
    ) {
        String aggregateScope = "player:" + playerId;
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthority.ReadRequirement.orEventual(requirement);
        long requiredRevision = effectiveRequirement.minimumRevision();
        Optional<DataAuthority.PlayerProfileSnapshot> effectiveSnapshot =
            snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            long observedRevision = deliveryReceipt == null ? 0L : deliveryReceipt.deliveredRevision();
            DataAuthority.ReadQuoteStatus status = requiredRevision > 0L || deliveryReceipt != null
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
            String message = deliveryReceipt == null
                ? null
                : "profile projection snapshot is missing despite authority delivery receipt";
            return DataAuthority.QuotedRead.unsatisfied(new DataAuthority.ReadQuote(
                aggregateScope,
                "player_profile",
                requiredRevision,
                observedRevision,
                status,
                null,
                message,
                DataAuthority.ReadProvenance.authority(),
                deliveryReceipt
            ));
        }

        DataAuthority.PlayerProfileSnapshot profileSnapshot = effectiveSnapshot.get();
        DataAuthority.SnapshotWatermark snapshotWatermark = profileSnapshot.watermark();
        long snapshotRevision = Math.max(0L, profileSnapshot.revision());
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        DataAuthority.ProjectionDeliveryReceipt effectiveReceipt = deliveryReceipt != null
            ? deliveryReceipt
            : DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_profile", snapshotWatermark);
        DataAuthority.ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = DataAuthority.ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(snapshotWatermark.aggregateScope())) {
            status = DataAuthority.ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (!expectedStateTopic("player_profile").equals(snapshotWatermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = DataAuthority.ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = DataAuthority.ReadQuoteStatus.STALE_REVISION;
        } else if (!receiptSatisfies(effectiveReceipt, "player_profile", aggregateScope, requiredRevision)) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(nowEpochMillis, effectiveRequirement.maxAgeMillis())) {
            status = DataAuthority.ReadQuoteStatus.EXPIRED;
        } else {
            status = DataAuthority.ReadQuoteStatus.SATISFIED;
        }

        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            aggregateScope,
            "player_profile",
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            DataAuthority.ReadProvenance.authority(),
            effectiveReceipt
        );
        return status == DataAuthority.ReadQuoteStatus.SATISFIED
            ? DataAuthority.QuotedRead.satisfied(profileSnapshot, quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private static DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quoteRankSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerRankSnapshot> snapshot,
        DataAuthority.ProjectionDeliveryReceipt deliveryReceipt,
        long nowEpochMillis
    ) {
        String aggregateScope = "rank:player:" + playerId;
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthority.ReadRequirement.orEventual(requirement);
        long requiredRevision = effectiveRequirement.minimumRevision();
        Optional<DataAuthority.PlayerRankSnapshot> effectiveSnapshot =
            snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            long observedRevision = deliveryReceipt == null ? 0L : deliveryReceipt.deliveredRevision();
            DataAuthority.ReadQuoteStatus status = requiredRevision > 0L || deliveryReceipt != null
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
            String message = deliveryReceipt == null
                ? null
                : "rank projection snapshot is missing despite authority delivery receipt";
            return DataAuthority.QuotedRead.unsatisfied(new DataAuthority.ReadQuote(
                aggregateScope,
                "player_rank",
                requiredRevision,
                observedRevision,
                status,
                null,
                message,
                DataAuthority.ReadProvenance.authority(),
                deliveryReceipt
            ));
        }

        DataAuthority.PlayerRankSnapshot rankSnapshot = effectiveSnapshot.get();
        DataAuthority.SnapshotWatermark snapshotWatermark = rankSnapshot.watermark();
        long snapshotRevision = Math.max(0L, rankSnapshot.revision());
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        DataAuthority.ProjectionDeliveryReceipt effectiveReceipt = deliveryReceipt != null
            ? deliveryReceipt
            : DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_rank", snapshotWatermark);
        DataAuthority.ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = DataAuthority.ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(snapshotWatermark.aggregateScope())) {
            status = DataAuthority.ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (!expectedStateTopic("player_rank").equals(snapshotWatermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = DataAuthority.ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = DataAuthority.ReadQuoteStatus.STALE_REVISION;
        } else if (!receiptSatisfies(effectiveReceipt, "player_rank", aggregateScope, requiredRevision)) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(nowEpochMillis, effectiveRequirement.maxAgeMillis())) {
            status = DataAuthority.ReadQuoteStatus.EXPIRED;
        } else {
            status = DataAuthority.ReadQuoteStatus.SATISFIED;
        }

        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            aggregateScope,
            "player_rank",
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            DataAuthority.ReadProvenance.authority(),
            effectiveReceipt
        );
        return status == DataAuthority.ReadQuoteStatus.SATISFIED
            ? DataAuthority.QuotedRead.satisfied(rankSnapshot, quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private static boolean receiptSatisfies(
        DataAuthority.ProjectionDeliveryReceipt receipt,
        String projectionFamily,
        String aggregateScope,
        long requiredRevision
    ) {
        return receipt != null
            && receipt.satisfies(projectionFamily, aggregateScope, requiredRevision)
            && expectedStateTopic(projectionFamily).equals(receipt.stateTopic());
    }

    private static String expectedStateTopic(String projectionFamily) {
        return "state." + projectionFamily;
    }

    private DataAuthority.SnapshotWatermark snapshotWatermark(
        ResultSet resultSet,
        String fallbackScope,
        String fallbackType,
        String fallbackId,
        long fallbackRevision
    ) throws SQLException {
        String aggregateScope = resultSet.getString("watermark_aggregate_scope");
        if (aggregateScope == null || aggregateScope.isBlank()) {
            return DataAuthority.SnapshotWatermark.unwatermarked(
                fallbackScope,
                fallbackType,
                fallbackId,
                fallbackRevision
            );
        }
        long sourceRevision = resultSet.getLong("watermark_revision");
        if (resultSet.wasNull()) {
            sourceRevision = fallbackRevision;
        }
        Timestamp eventCreatedAt = resultSet.getTimestamp("watermark_event_created_at");
        return new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            aggregateScope,
            resultSet.getString("watermark_aggregate_type"),
            resultSet.getString("watermark_aggregate_id"),
            resultSet.getString("watermark_command_domain"),
            resultSet.getString("watermark_state_topic"),
            resultSet.getString("watermark_partition_key"),
            resultSet.getObject("watermark_command_id", UUID.class),
            resultSet.getObject("watermark_event_id", UUID.class),
            sourceRevision,
            eventCreatedAt == null ? 0L : eventCreatedAt.toInstant().toEpochMilli(),
            resultSet.getString("watermark_state_fingerprint"),
            resultSet.getString("watermark_event_chain_hash")
        );
    }

    private DataAuthority.CommandResult execute(DataAuthority.AuthorityCommand command) {
        try (Connection connection = connectionAdapter.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CommandFingerprint fingerprint = fingerprint(command);
                DataAuthority.CommandResult existing = findExistingResult(connection, command, fingerprint);
                if (existing != null) {
                    connection.commit();
                    return existing;
                }
                DataAuthority.CommandResult contractRejection = contractRejection(command);
                if (contractRejection != null) {
                    recordCommand(connection, command, contractRejection, fingerprint);
                    connection.commit();
                    return contractRejection;
                }
                if (isExpired(command)) {
                    DataAuthority.CommandResult result = rejected(command,
                        DataAuthority.RejectionReason.EXPIRED_DEADLINE, "Command deadline expired");
                    recordCommand(connection, command, result, fingerprint);
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

                recordCommand(connection, command, result, fingerprint);
                if (result.accepted()) {
                    DataAuthority.CommandSettlement settlement = recordAuthorityEventAndSnapshot(
                        connection,
                        command,
                        result
                    );
                    result = result.withSettlement(settlement);
                    recordCommandEventReceipt(
                        connection,
                        command.commandId(),
                        settlement.watermark().sourceEventId(),
                        command.type().name(),
                        settlement
                    );
                }
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

    private DataAuthority.CommandResult contractRejection(DataAuthority.AuthorityCommand command) {
        try {
            DataAuthorityCommandContracts.validate(command);
            return null;
        } catch (DataAuthorityCommandContracts.CommandContractViolation violation) {
            return rejected(command, violation.rejectionReason(), violation.getMessage());
        } catch (IllegalArgumentException exception) {
            return rejected(command, DataAuthority.RejectionReason.VALIDATION_FAILED, exception.getMessage());
        }
    }

    private DataAuthority.CommandResult persistPlayerProfile(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        boolean online
    ) throws SQLException {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "playerId is required");
        }

        AggregateClaim claim = claimAggregate(connection, command);
        if (!claim.accepted()) {
            return claim.rejection();
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

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_profiles (
                player_id, username, normalized_username, first_seen, last_seen, online,
                current_server, current_proxy, last_ip, profile_data, revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (player_id) DO UPDATE SET
                username = EXCLUDED.username,
                normalized_username = EXCLUDED.normalized_username,
                last_seen = EXCLUDED.last_seen,
                online = EXCLUDED.online,
                current_server = EXCLUDED.current_server,
                current_proxy = EXCLUDED.current_proxy,
                last_ip = COALESCE(EXCLUDED.last_ip, player_profiles.last_ip),
                profile_data = player_profiles.profile_data || EXCLUDED.profile_data,
                revision = EXCLUDED.revision,
                updated_at = EXCLUDED.updated_at
            """)) {
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
            statement.setLong(11, claim.revision());
            statement.setTimestamp(12, timestamp);
            statement.executeUpdate();
        }

        if (command.type() == DataAuthority.CommandType.START_SESSION
            || command.type() == DataAuthority.CommandType.RENEW_SESSION
            || command.type() == DataAuthority.CommandType.END_SESSION) {
            persistPlayerSession(connection, command, playerId, timestamp);
        }

        return accepted(command, claim.revision(),
            online ? "Player profile/session updated" : "Player profile/session closed");
    }

    private void persistPlayerSession(
        Connection connection,
        DataAuthority.AuthorityCommand command,
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
        DataAuthority.AuthorityCommand command
    ) throws SQLException {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "playerId is required");
        }

        AggregateClaim claim = claimAggregate(connection, command);
        if (!claim.accepted()) {
            return claim.rejection();
        }

        String primaryRank = string(command.payload(), "primaryRank", "DEFAULT");
        List<String> ranks = stringList(command.payload().get("ranks"));
        if (ranks.isEmpty()) {
            ranks = List.of(primaryRank);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_rank_projection (player_id, primary_rank, ranks, revision, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (player_id) DO UPDATE SET
                primary_rank = EXCLUDED.primary_rank,
                ranks = EXCLUDED.ranks,
                revision = EXCLUDED.revision,
                updated_at = CURRENT_TIMESTAMP
            """)) {
            statement.setObject(1, playerId);
            statement.setString(2, primaryRank);
            statement.setArray(3, connection.createArrayOf("text", ranks.toArray(String[]::new)));
            statement.setLong(4, claim.revision());
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

        return accepted(command, claim.revision(), "Rank projection updated");
    }

    private DataAuthority.CommandResult persistMatchStart(
        Connection connection,
        DataAuthority.AuthorityCommand command
    ) throws SQLException {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "matchId is required");
        }

        AggregateClaim claim = claimAggregate(connection, command);
        if (!claim.accepted()) {
            return claim.rejection();
        }

        Map<String, Object> payload = command.payload();
        Timestamp startedAt = timestamp(longValue(payload, "startedAt",
            longValue(payload, "timestamp", System.currentTimeMillis())));

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO match_records (
                match_id, family_id, map_id, server_id, slot_id, state, started_at, metadata, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (match_id) DO UPDATE SET
                family_id = EXCLUDED.family_id,
                map_id = COALESCE(EXCLUDED.map_id, match_records.map_id),
                server_id = COALESCE(EXCLUDED.server_id, match_records.server_id),
                slot_id = COALESCE(EXCLUDED.slot_id, match_records.slot_id),
                state = EXCLUDED.state,
                started_at = COALESCE(match_records.started_at, EXCLUDED.started_at),
                metadata = match_records.metadata || EXCLUDED.metadata,
                revision = EXCLUDED.revision
            """)) {
            statement.setObject(1, matchId);
            statement.setString(2, string(payload, "familyId", "unknown"));
            setNullableString(statement, 3, string(payload, "mapId", null));
            setNullableString(statement, 4, string(payload, "serverId", null));
            setNullableString(statement, 5, string(payload, "slotId", null));
            statement.setString(6, string(payload, "state", "STARTED"));
            statement.setTimestamp(7, startedAt);
            statement.setString(8, gson.toJson(payload));
            statement.setLong(9, claim.revision());
            statement.executeUpdate();
        }

        return accepted(command, claim.revision(), "Match record started");
    }

    private DataAuthority.CommandResult persistMatchEnd(
        Connection connection,
        DataAuthority.AuthorityCommand command
    ) throws SQLException {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE, "matchId is required");
        }

        AggregateClaim claim = claimAggregate(connection, command);
        if (!claim.accepted()) {
            return claim.rejection();
        }

        Map<String, Object> payload = command.payload();
        Timestamp endedAt = timestamp(longValue(payload, "endedAt",
            longValue(payload, "timestamp", System.currentTimeMillis())));

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO match_records (
                match_id, family_id, map_id, server_id, slot_id, state, started_at, ended_at, metadata, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (match_id) DO UPDATE SET
                family_id = EXCLUDED.family_id,
                map_id = COALESCE(EXCLUDED.map_id, match_records.map_id),
                server_id = COALESCE(EXCLUDED.server_id, match_records.server_id),
                slot_id = COALESCE(EXCLUDED.slot_id, match_records.slot_id),
                state = EXCLUDED.state,
                ended_at = EXCLUDED.ended_at,
                metadata = match_records.metadata || EXCLUDED.metadata,
                revision = EXCLUDED.revision
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
            statement.setLong(10, claim.revision());
            statement.executeUpdate();
        }

        persistMatchParticipants(connection, matchId, payload.get("participants"));
        return accepted(command, claim.revision(), "Match record ended");
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

    private DataAuthority.CommandResult findExistingResult(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        CommandFingerprint fingerprint
    )
        throws SQLException {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return null;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT command_id, accepted, rejection_reason, result_message, result_revision,
                   command_fingerprint, result_payload::text AS result_payload
            FROM authority_commands
            WHERE idempotency_key = ?
            """)) {
            statement.setString(1, command.idempotencyKey());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                String existingFingerprint = resultSet.getString("command_fingerprint");
                if (existingFingerprint != null && !existingFingerprint.equals(fingerprint.commandFingerprint())) {
                    recordIdempotencyConflict(connection, command, resultSet.getObject("command_id", UUID.class),
                        existingFingerprint, fingerprint.commandFingerprint());
                    return rejected(command, DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT,
                        "Idempotency key was already used for different command material");
                }

                UUID commandId = resultSet.getObject("command_id", UUID.class);
                boolean accepted = resultSet.getBoolean("accepted");
                String rejection = resultSet.getString("rejection_reason");
                DataAuthority.RejectionReason reason = rejection == null
                    ? null
                    : DataAuthority.RejectionReason.valueOf(rejection);
                long revision = resultSet.getLong("result_revision");
                return new DataAuthority.CommandResult(
                    commandId,
                    accepted,
                    revision,
                    reason,
                    resultSet.getString("result_message"),
                    DataAuthority.CommandSettlement.fromPayload(
                        jsonMap(resultSet.getString("result_payload")),
                        DataAuthority.CommandSettlement.unsettled(revision)
                    )
                );
            }
        }
    }

    private void recordCommand(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        CommandFingerprint fingerprint
    ) throws SQLException {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_commands (
                command_id, command_type, schema_version, actor, scope, idempotency_key, deadline_at,
                fencing_token, expected_revision, payload, accepted, rejection_reason,
                result_message, result_payload, result_revision, payload_hash, command_fingerprint, provenance,
                verified_principal, command_domain, command_topic, partition_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)) {
            statement.setObject(1, command.commandId());
            statement.setString(2, command.type().name());
            statement.setInt(3, command.manifest().schemaVersion());
            statement.setString(4, command.actorId());
            statement.setString(5, command.scope());
            statement.setString(6, command.idempotencyKey());
            if (command.deadlineEpochMillis() > 0) {
                statement.setTimestamp(7, timestamp(command.deadlineEpochMillis()));
            } else {
                statement.setNull(7, Types.TIMESTAMP);
            }
            setNullableString(statement, 8, command.fencingToken());
            statement.setLong(9, command.expectedRevision());
            statement.setString(10, gson.toJson(command.payload()));
            statement.setBoolean(11, result.accepted());
            statement.setString(12, result.rejectionReason() == null ? null : result.rejectionReason().name());
            statement.setString(13, result.message());
            statement.setString(14, gson.toJson(result.settlement().payload()));
            statement.setLong(15, result.revision());
            statement.setString(16, fingerprint.payloadHash());
            statement.setString(17, fingerprint.commandFingerprint());
            statement.setString(18, gson.toJson(provenancePayload(command)));
            statement.setString(19, command.provenance().verifiedPrincipal());
            statement.setString(20, route.domain());
            statement.setString(21, route.commandTopic());
            statement.setString(22, route.partitionKey());
            statement.executeUpdate();
        }
    }

    private void recordIdempotencyConflict(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        UUID originalCommandId,
        String expectedFingerprint,
        String actualFingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_idempotency_conflicts (
                original_command_id, attempted_command_id, idempotency_key, command_type, actor,
                scope, expected_fingerprint, actual_fingerprint, rejection_reason, verified_principal
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setObject(1, originalCommandId);
            statement.setObject(2, command.commandId());
            statement.setString(3, command.idempotencyKey());
            statement.setString(4, command.type().name());
            statement.setString(5, command.actorId());
            statement.setString(6, command.scope());
            statement.setString(7, expectedFingerprint);
            statement.setString(8, actualFingerprint);
            statement.setString(9, DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT.name());
            statement.setString(10, command.provenance().verifiedPrincipal());
            statement.executeUpdate();
        }
    }

    private DataAuthority.CommandSettlement recordAuthorityEventAndSnapshot(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) throws SQLException {
        AggregateMetadata aggregate = aggregateMetadata(command);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> eventPayload = authorityPayload(command, result);
        Map<String, Object> statePayload = statePayload(connection, command, result, eventPayload);
        Timestamp eventCreatedAt = Timestamp.from(Instant.now());
        String eventFingerprint = hash(canonicalJson(eventPayload));
        String stateFingerprint = hash(canonicalJson(statePayload));
        EventChainHash chainHash = eventChainHash(
            connection,
            aggregate,
            eventId,
            command,
            result,
            eventCreatedAt,
            eventFingerprint
        );

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_events (
                event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                revision, event_type, payload, provenance, created_at,
                hash_version, previous_chain_hash, chain_hash,
                command_domain, event_topic, partition_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setObject(1, eventId);
            statement.setObject(2, command.commandId());
            statement.setString(3, aggregate.scope());
            statement.setString(4, aggregate.type());
            statement.setString(5, aggregate.id());
            statement.setLong(6, result.revision());
            statement.setString(7, command.type().name());
            statement.setString(8, gson.toJson(eventPayload));
            statement.setString(9, gson.toJson(provenancePayload(command)));
            statement.setTimestamp(10, eventCreatedAt);
            statement.setInt(11, chainHash.hashVersion());
            statement.setString(12, chainHash.previousChainHash());
            statement.setString(13, chainHash.chainHash());
            statement.setString(14, route.domain());
            statement.setString(15, route.eventTopic());
            statement.setString(16, route.partitionKey());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_state_changelog (
                event_id, command_id, aggregate_scope, aggregate_type, aggregate_id, revision,
                command_domain, state_topic, partition_key, state_payload, state_fingerprint,
                event_fingerprint, event_chain_hash, event_created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)) {
            statement.setObject(1, eventId);
            statement.setObject(2, command.commandId());
            statement.setString(3, aggregate.scope());
            statement.setString(4, aggregate.type());
            statement.setString(5, aggregate.id());
            statement.setLong(6, result.revision());
            statement.setString(7, route.domain());
            statement.setString(8, route.stateTopic());
            statement.setString(9, route.partitionKey());
            statement.setString(10, gson.toJson(statePayload));
            statement.setString(11, stateFingerprint);
            statement.setString(12, eventFingerprint);
            statement.setString(13, chainHash.chainHash());
            statement.setTimestamp(14, eventCreatedAt);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_state_snapshots (
                aggregate_scope, aggregate_type, aggregate_id, revision, command_id, event_id,
                event_created_at, event_fingerprint, event_chain_hash, state_fingerprint, state_payload,
                command_domain, state_topic, partition_key, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (aggregate_scope) DO UPDATE SET
                aggregate_type = EXCLUDED.aggregate_type,
                aggregate_id = EXCLUDED.aggregate_id,
                revision = EXCLUDED.revision,
                command_id = EXCLUDED.command_id,
                event_id = EXCLUDED.event_id,
                event_created_at = EXCLUDED.event_created_at,
                event_fingerprint = EXCLUDED.event_fingerprint,
                event_chain_hash = EXCLUDED.event_chain_hash,
                state_fingerprint = EXCLUDED.state_fingerprint,
                state_payload = EXCLUDED.state_payload,
                command_domain = EXCLUDED.command_domain,
                state_topic = EXCLUDED.state_topic,
                partition_key = EXCLUDED.partition_key,
                updated_at = CURRENT_TIMESTAMP
            WHERE authority_state_snapshots.revision <= EXCLUDED.revision
            """)) {
            statement.setString(1, aggregate.scope());
            statement.setString(2, aggregate.type());
            statement.setString(3, aggregate.id());
            statement.setLong(4, result.revision());
            statement.setObject(5, command.commandId());
            statement.setObject(6, eventId);
            statement.setTimestamp(7, eventCreatedAt);
            statement.setString(8, eventFingerprint);
            statement.setString(9, chainHash.chainHash());
            statement.setString(10, stateFingerprint);
            statement.setString(11, gson.toJson(statePayload));
            statement.setString(12, route.domain());
            statement.setString(13, route.stateTopic());
            statement.setString(14, route.partitionKey());
            statement.executeUpdate();
        }
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            aggregate.scope(),
            aggregate.type(),
            aggregate.id(),
            route.domain(),
            route.stateTopic(),
            route.partitionKey(),
            command.commandId(),
            eventId,
            result.revision(),
            eventCreatedAt.toInstant().toEpochMilli(),
            stateFingerprint,
            chainHash.chainHash()
        );
        return new DataAuthority.CommandSettlement(
            "postgres-authority-state",
            route.domain(),
            route.commandTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            command.fencingToken(),
            command.idempotencyKey(),
            command.expectedRevision(),
            watermark
        );
    }

    private EventChainHash eventChainHash(
        Connection connection,
        AggregateMetadata aggregate,
        UUID eventId,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        Timestamp eventCreatedAt,
        String eventFingerprint
    ) throws SQLException {
        String previousChainHash = previousChainHash(connection, aggregate.scope(), result.revision());
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("hashVersion", AUTHORITY_EVENT_CHAIN_HASH_VERSION);
        material.put("previousChainHash", previousChainHash);
        material.put("eventId", eventId.toString());
        material.put("commandId", command.commandId().toString());
        material.put("aggregateScope", aggregate.scope());
        material.put("aggregateType", aggregate.type());
        material.put("aggregateId", aggregate.id());
        material.put("revision", result.revision());
        material.put("eventType", command.type().name());
        material.put("eventFingerprint", eventFingerprint);
        material.put("createdAt", eventCreatedAt.toInstant().toString());
        return new EventChainHash(
            AUTHORITY_EVENT_CHAIN_HASH_VERSION,
            previousChainHash,
            hash(canonicalJson(material))
        );
    }

    private String previousChainHash(Connection connection, String aggregateScope, long revision)
        throws SQLException {
        if (revision <= 1L) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT chain_hash
            FROM authority_events
            WHERE aggregate_scope = ? AND revision = ?
            """)) {
            statement.setString(1, aggregateScope);
            statement.setLong(2, revision - 1L);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                        "Missing authority event chain predecessor for "
                            + aggregateScope + " revision " + (revision - 1L)
                    );
                }
                return resultSet.getString("chain_hash");
            }
        }
    }

    private void recordCommandEventReceipt(
        Connection connection,
        UUID commandId,
        UUID eventId,
        String eventType,
        DataAuthority.CommandSettlement settlement
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE authority_commands
            SET result_event_id = ?, result_event_type = ?, result_payload = ?::jsonb
            WHERE command_id = ?
            """)) {
            statement.setObject(1, eventId);
            statement.setString(2, eventType);
            statement.setString(3, gson.toJson(settlement.payload()));
            statement.setObject(4, commandId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No authority command row found for event receipt " + commandId);
            }
        }
    }

    private AggregateClaim claimAggregate(Connection connection, DataAuthority.AuthorityCommand command)
        throws SQLException {
        String aggregateKey = aggregateKey(command);
        AuthorityWriterClaimToken writerClaimToken;
        long fencingToken;
        try {
            writerClaimToken = AuthorityWriterClaimToken.parse(command.fencingToken());
            fencingToken = writerClaimToken == null
                ? fencingToken(command.fencingToken())
                : writerClaimToken.epoch();
        } catch (IllegalArgumentException exception) {
            return AggregateClaim.rejected(rejected(
                command,
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                exception.getMessage()
            ));
        }

        if (writerClaimToken != null) {
            AggregateClaim writerClaimRejection = rejectInvalidWriterClaim(connection, command, writerClaimToken);
            if (writerClaimRejection != null) {
                return writerClaimRejection;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT revision, last_fencing_token
            FROM authority_aggregate_versions
            WHERE scope = ?
            FOR UPDATE
            """)) {
            statement.setString(1, aggregateKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long currentRevision = resultSet.getLong("revision");
                    long currentFence = resultSet.getLong("last_fencing_token");
                    AggregateClaim rejection = rejectStaleClaim(command, currentRevision, currentFence, fencingToken);
                    if (rejection != null) {
                        return rejection;
                    }
                    long nextRevision = currentRevision + 1L;
                    updateAggregateVersion(connection, aggregateKey, nextRevision, Math.max(currentFence, fencingToken));
                    return AggregateClaim.accepted(nextRevision);
                }
            }
        }

        if (command.expectedRevision() != DataAuthority.ANY_REVISION && command.expectedRevision() != 0L) {
            return AggregateClaim.rejected(rejected(
                command,
                DataAuthority.RejectionReason.STALE_REVISION,
                "Expected revision " + command.expectedRevision() + " but aggregate does not exist"
            ));
        }

        long nextRevision = 1L;
        if (insertAggregateVersion(connection, aggregateKey, nextRevision, fencingToken)) {
            return AggregateClaim.accepted(nextRevision);
        }
        return claimAggregate(connection, command);
    }

    private AggregateClaim rejectInvalidWriterClaim(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        AuthorityWriterClaimToken writerClaimToken
    ) throws SQLException {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT c.command_domain,
                   c.command_topic,
                   c.partition_key,
                   c.owner_node,
                   c.epoch,
                   c.previous_owner_node,
                   c.previous_epoch,
                   c.claimed_at,
                   c.claim_fingerprint,
                   p.owner_node AS current_owner_node,
                   p.epoch AS current_epoch
            FROM authority_writer_claims c
            LEFT JOIN authority_partition_epochs p
              ON p.command_domain = c.command_domain
             AND p.partition_key = c.partition_key
            WHERE c.claim_id = ?
            """)) {
            statement.setObject(1, writerClaimToken.claimId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return AggregateClaim.rejected(rejected(
                        command,
                        DataAuthority.RejectionReason.VALIDATION_FAILED,
                        "Authority writer claim receipt was not found"
                    ));
                }
                String commandDomain = resultSet.getString("command_domain");
                String commandTopic = resultSet.getString("command_topic");
                String partitionKey = resultSet.getString("partition_key");
                String ownerNode = resultSet.getString("owner_node");
                long epoch = resultSet.getLong("epoch");
                String fingerprint = resultSet.getString("claim_fingerprint");
                if (epoch != writerClaimToken.epoch() || !fingerprint.equals(writerClaimToken.claimFingerprint())) {
                    return AggregateClaim.rejected(rejected(
                        command,
                        DataAuthority.RejectionReason.VALIDATION_FAILED,
                        "Authority writer claim receipt fingerprint does not match persisted claim"
                    ));
                }
                String expectedFingerprint = AuthorityWriterClaim.fingerprint(
                    writerClaimToken.claimId(),
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    epoch,
                    resultSet.getString("previous_owner_node"),
                    resultSet.getLong("previous_epoch"),
                    resultSet.getTimestamp("claimed_at").toInstant()
                );
                if (!fingerprint.equals(expectedFingerprint)) {
                    return AggregateClaim.rejected(rejected(
                        command,
                        DataAuthority.RejectionReason.VALIDATION_FAILED,
                        "Authority writer claim receipt fingerprint failed verification"
                    ));
                }
                if (!route.domain().equals(commandDomain)
                    || !route.commandTopic().equals(commandTopic)
                    || !route.partitionKey().equals(partitionKey)) {
                    return AggregateClaim.rejected(rejected(
                        command,
                        DataAuthority.RejectionReason.INVALID_SCOPE,
                        "Authority writer claim receipt route does not match command route"
                    ));
                }
                String currentOwnerNode = resultSet.getString("current_owner_node");
                long currentEpoch = resultSet.getLong("current_epoch");
                if (currentOwnerNode == null || currentEpoch != epoch || !currentOwnerNode.equals(ownerNode)) {
                    return AggregateClaim.rejected(rejected(
                        command,
                        DataAuthority.RejectionReason.STALE_FENCING_TOKEN,
                        "Authority writer claim " + writerClaimToken.claimId()
                            + " for epoch " + epoch
                            + " is older than current partition owner epoch " + currentEpoch
                    ));
                }
            }
        }
        return null;
    }

    private static String aggregateKey(DataAuthority.AuthorityCommand command) {
        return AuthorityCommandRoute.fromCommand(command).partitionKey();
    }

    private AggregateClaim rejectStaleClaim(
        DataAuthority.AuthorityCommand command,
        long currentRevision,
        long currentFence,
        long fencingToken
    ) {
        if (fencingToken < currentFence) {
            return AggregateClaim.rejected(rejected(
                command,
                DataAuthority.RejectionReason.STALE_FENCING_TOKEN,
                "Fencing token " + fencingToken + " is older than current token " + currentFence
            ));
        }
        if (command.expectedRevision() != DataAuthority.ANY_REVISION
            && command.expectedRevision() != currentRevision) {
            return AggregateClaim.rejected(rejected(
                command,
                DataAuthority.RejectionReason.STALE_REVISION,
                "Expected revision " + command.expectedRevision() + " but current revision is " + currentRevision
            ));
        }
        return null;
    }

    private boolean insertAggregateVersion(Connection connection, String scope, long revision, long fencingToken)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_aggregate_versions (scope, revision, last_fencing_token, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (scope) DO NOTHING
            """)) {
            statement.setString(1, scope);
            statement.setLong(2, revision);
            statement.setLong(3, fencingToken);
            return statement.executeUpdate() > 0;
        }
    }

    private void updateAggregateVersion(Connection connection, String scope, long revision, long fencingToken)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE authority_aggregate_versions
            SET revision = ?, last_fencing_token = ?, updated_at = CURRENT_TIMESTAMP
            WHERE scope = ?
            """)) {
            statement.setLong(1, revision);
            statement.setLong(2, fencingToken);
            statement.setString(3, scope);
            statement.executeUpdate();
        }
    }

    private UUID playerId(DataAuthority.AuthorityCommand command) {
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
        return uuid(value);
    }

    private UUID matchId(DataAuthority.AuthorityCommand command) {
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
        DataAuthority.AuthorityCommand command,
        long revision,
        String message
    ) {
        return new DataAuthority.CommandResult(command.commandId(), true, revision,
            DataAuthority.RejectionReason.NONE, message);
    }

    private DataAuthority.CommandResult rejected(
        DataAuthority.AuthorityCommand command,
        DataAuthority.RejectionReason reason,
        String message
    ) {
        return new DataAuthority.CommandResult(command.commandId(), false, command.expectedRevision(), reason, message);
    }

    private static long fencingToken(String token) {
        if (token == null || token.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("fencingToken must be a numeric authority epoch");
        }
    }

    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = gson.fromJson(json, Map.class);
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : parsed.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private static List<String> arrayToStrings(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
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

    private static boolean isExpired(DataAuthority.AuthorityCommand command) {
        return command.deadlineEpochMillis() > 0L && command.deadlineEpochMillis() < System.currentTimeMillis();
    }

    private static LifecyclePolicyRequirement monthly(
        String timestampColumn,
        String lifecycleClass,
        int minRetentionDays
    ) {
        return new LifecyclePolicyRequirement(timestampColumn, lifecycleClass, "MONTHLY_RANGE", minRetentionDays);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private CommandFingerprint fingerprint(DataAuthority.AuthorityCommand command) {
        AuthorityCommandFingerprints.Fingerprint fingerprint = AuthorityCommandFingerprints.fingerprint(command);
        return new CommandFingerprint(fingerprint.payloadHash(), fingerprint.commandFingerprint());
    }

    private Map<String, Object> provenancePayload(DataAuthority.AuthorityCommand command) {
        Map<String, Object> values = new LinkedHashMap<>(command.provenance().payload());
        values.put("authorityProvider", "postgres");
        if ("unknown".equals(values.get("providerKind"))) {
            values.put("providerKind", "postgres-local");
        }
        return values;
    }

    private String canonicalJson(Object value) {
        return gson.toJson(canonicalValue(value));
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    sorted.put(entry.getKey().toString(), canonicalValue(entry.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : values) {
                normalized.add(canonicalValue(item));
            }
            return normalized;
        }
        if (value instanceof Number number) {
            return canonicalNumber(number);
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        return value;
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Byte || number instanceof Short
            || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue()).toPlainString();
        }
        return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AggregateMetadata aggregateMetadata(DataAuthority.AuthorityCommand command) {
        return switch (command.type()) {
            case GRANT_RANK, REVOKE_RANK -> {
                UUID playerId = playerId(command);
                yield new AggregateMetadata(
                    aggregateKey(command),
                    "player_rank",
                    playerId == null ? command.scope() : playerId.toString()
                );
            }
            case RECORD_MATCH_START, RECORD_MATCH_END -> {
                UUID matchId = matchId(command);
                yield new AggregateMetadata(
                    aggregateKey(command),
                    "match",
                    matchId == null ? command.scope() : matchId.toString()
                );
            }
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT, START_SESSION, RENEW_SESSION, END_SESSION -> {
                UUID playerId = playerId(command);
                yield new AggregateMetadata(
                    aggregateKey(command),
                    "player_profile",
                    playerId == null ? command.scope() : playerId.toString()
                );
            }
        };
    }

    private Map<String, Object> statePayload(
        Connection connection,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        Map<String, Object> fallback
    ) throws SQLException {
        return switch (command.type()) {
            case GRANT_RANK, REVOKE_RANK -> rankStatePayload(connection, playerId(command), result.revision(), fallback);
            case RECORD_MATCH_START, RECORD_MATCH_END -> matchStatePayload(connection, matchId(command), result.revision(), fallback);
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT, START_SESSION, RENEW_SESSION, END_SESSION ->
                profileStatePayload(connection, playerId(command), result.revision(), fallback);
        };
    }

    private Map<String, Object> profileStatePayload(
        Connection connection,
        UUID playerId,
        long revision,
        Map<String, Object> fallback
    ) throws SQLException {
        if (playerId == null) {
            return fallback;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_id, username, normalized_username, online, current_server, current_proxy,
                   total_playtime_ms, profile_data::text AS profile_data, revision
            FROM player_profiles
            WHERE player_id = ?
            """)) {
            statement.setObject(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return fallback;
                }
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("playerId", resultSet.getObject("player_id", UUID.class).toString());
                state.put("username", resultSet.getString("username"));
                state.put("normalizedUsername", resultSet.getString("normalized_username"));
                state.put("online", resultSet.getBoolean("online"));
                state.put("currentServer", resultSet.getString("current_server"));
                state.put("currentProxy", resultSet.getString("current_proxy"));
                state.put("totalPlaytimeMs", resultSet.getLong("total_playtime_ms"));
                state.put("profileData", jsonMap(resultSet.getString("profile_data")));
                state.put("revision", resultSet.getLong("revision"));
                return state;
            }
        }
    }

    private Map<String, Object> rankStatePayload(
        Connection connection,
        UUID playerId,
        long revision,
        Map<String, Object> fallback
    ) throws SQLException {
        if (playerId == null) {
            return fallback;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_id, primary_rank, ranks, revision
            FROM player_rank_projection
            WHERE player_id = ?
            """)) {
            statement.setObject(1, playerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return fallback;
                }
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("playerId", resultSet.getObject("player_id", UUID.class).toString());
                state.put("primaryRank", resultSet.getString("primary_rank"));
                state.put("ranks", arrayToStrings(resultSet.getArray("ranks")));
                state.put("revision", resultSet.getLong("revision"));
                return state;
            }
        }
    }

    private Map<String, Object> matchStatePayload(
        Connection connection,
        UUID matchId,
        long revision,
        Map<String, Object> fallback
    ) throws SQLException {
        if (matchId == null) {
            return fallback;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT match_id, family_id, map_id, server_id, slot_id, state,
                   started_at, ended_at, metadata::text AS metadata, revision
            FROM match_records
            WHERE match_id = ?
            """)) {
            statement.setObject(1, matchId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return fallback;
                }
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("matchId", resultSet.getObject("match_id", UUID.class).toString());
                state.put("familyId", resultSet.getString("family_id"));
                state.put("mapId", resultSet.getString("map_id"));
                state.put("serverId", resultSet.getString("server_id"));
                state.put("slotId", resultSet.getString("slot_id"));
                state.put("state", resultSet.getString("state"));
                state.put("startedAt", epochMillis(resultSet.getTimestamp("started_at")));
                state.put("endedAt", epochMillis(resultSet.getTimestamp("ended_at")));
                state.put("metadata", jsonMap(resultSet.getString("metadata")));
                state.put("revision", resultSet.getLong("revision"));
                return state;
            }
        }
    }

    private Map<String, Object> authorityPayload(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", command.commandId().toString());
        payload.put("commandType", command.type().name());
        payload.put("actorId", command.actorId());
        payload.put("scope", command.scope());
        payload.put("revision", result.revision());
        payload.put("route", AuthorityCommandRoute.fromCommand(command).payload());
        payload.put("provenance", provenancePayload(command));
        payload.put("payload", command.payload());
        return payload;
    }

    private static Long epochMillis(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private record CommandFingerprint(String payloadHash, String commandFingerprint) {
    }

    private record EventChainHash(int hashVersion, String previousChainHash, String chainHash) {
    }

    private record AggregateMetadata(String scope, String type, String id) {
    }

    private record LifecyclePolicyRequirement(
        String timestampColumn,
        String lifecycleClass,
        String partitionStrategy,
        int minRetentionDays
    ) {
    }

    private record AggregateClaim(long revision, DataAuthority.CommandResult rejection) {
        private static AggregateClaim accepted(long revision) {
            return new AggregateClaim(revision, null);
        }

        private static AggregateClaim rejected(DataAuthority.CommandResult rejection) {
            return new AggregateClaim(rejection.revision(), rejection);
        }

        private boolean accepted() {
            return rejection == null;
        }
    }
}
