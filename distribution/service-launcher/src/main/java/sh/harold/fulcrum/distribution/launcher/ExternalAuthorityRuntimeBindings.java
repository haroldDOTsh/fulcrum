package sh.harold.fulcrum.distribution.launcher;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.data.artifact.ArtifactMetadata;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceipt;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataState;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.presence.PresenceReceipt;
import sh.harold.fulcrum.data.presence.PresenceSnapshot;
import sh.harold.fulcrum.data.presence.PresenceState;
import sh.harold.fulcrum.data.route.RouteReceipt;
import sh.harold.fulcrum.data.route.RouteSnapshot;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionReceipt;
import sh.harold.fulcrum.data.session.SessionSnapshot;
import sh.harold.fulcrum.data.session.SessionState;
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectReceipt;
import sh.harold.fulcrum.data.subject.SubjectSnapshot;
import sh.harold.fulcrum.data.subject.SubjectState;
import sh.harold.fulcrum.data.store.cassandra.CassandraAuthorityProjectionWriter;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityCommandDecoder;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityCommandSource;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionSink;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionTopics;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityOffsetCommitter;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorder;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorderConfig;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStore;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStoreConfig;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec;
import sh.harold.fulcrum.data.store.valkey.ValkeyAuthorityCacheSink;
import sh.harold.fulcrum.data.store.valkey.ValkeyIdempotencyLedger;
import sh.harold.fulcrum.data.store.valkey.ValkeyStoredAuthorityDecisionCodec;
import sh.harold.fulcrum.standard.economy.EconomyBalanceSnapshot;
import sh.harold.fulcrum.standard.economy.EconomyReceipt;
import sh.harold.fulcrum.standard.economy.EconomyState;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.profile.PlayerProfileSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileReceipt;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentReceipt;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankReceipt;
import sh.harold.fulcrum.standard.rank.RankState;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;
import sh.harold.fulcrum.standard.stats.StatsCounterSnapshot;
import sh.harold.fulcrum.standard.stats.StatsReceipt;
import sh.harold.fulcrum.standard.stats.StatsState;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class ExternalAuthorityRuntimeBindings implements AuthorityRuntimeBindings {
    private static final String SUBJECT = "subject";
    private static final String PRESENCE = "presence";
    private static final String ROUTE = "route";
    private static final String SESSION = "session";
    private static final String ARTIFACT_METADATA = "artifact-metadata";
    private static final String PLAYER_PROFILE = StandardCapabilityAuthorityWireCodec.PLAYER_PROFILE_DOMAIN;
    private static final String RANK = StandardCapabilityAuthorityWireCodec.RANK_DOMAIN;
    private static final String PUNISHMENT = StandardCapabilityAuthorityWireCodec.PUNISHMENT_DOMAIN;
    private static final String ECONOMY = StandardCapabilityAuthorityWireCodec.ECONOMY_DOMAIN;
    private static final String STATS = StandardCapabilityAuthorityWireCodec.STATS_DOMAIN;
    private static final Duration KAFKA_POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Duration KAFKA_SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final JdbcAuthorityRecordStoreConfig RECORD_STORE =
            new JdbcAuthorityRecordStoreConfig("authority_records");
    private static final JdbcAuthorityDecisionRecorderConfig DECISION_RECORDER =
            new JdbcAuthorityDecisionRecorderConfig("authority_decisions");

    private final RuntimeExternalClients.AuthorityClients clients;

    ExternalAuthorityRuntimeBindings(RuntimeExternalClients.AuthorityClients clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    @Override
    public <C extends CommandPayload> AuthorityCommandSource<C> commandSource(String authorityDomain) {
        KafkaClientBundle kafka = kafka(authorityDomain);
        kafka.subscribe(List.of(commandTopic(authorityDomain)));
        return new KafkaAuthorityCommandSource<>(kafka.consumer(), KAFKA_POLL_TIMEOUT, commandDecoder(authorityDomain));
    }

    @Override
    public <S> AuthorityRecordStore<S> recordStore(
            String authorityDomain,
            Supplier<AuthorityRecord<S>> emptyRecord) {
        return new JdbcAuthorityRecordStore<>(
                clients.postgres().dataSource(),
                RECORD_STORE,
                stateCodec(authorityDomain),
                emptyRecord);
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> projectionWriter(String authorityDomain) {
        if (PRESENCE.equals(authorityDomain)) {
            return castPresenceProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::presenceProjectionStatement));
        }
        if (SUBJECT.equals(authorityDomain)) {
            return castSubjectProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::subjectProjectionStatement));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castRouteProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::routeProjectionStatement));
        }
        if (SESSION.equals(authorityDomain)) {
            return castSessionProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::sessionProjectionStatement));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castArtifactProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::artifactProjectionStatement));
        }
        if (PLAYER_PROFILE.equals(authorityDomain)) {
            return castPlayerProfileProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::playerProfileProjectionStatement));
        }
        if (RANK.equals(authorityDomain)) {
            return castRankProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::rankProjectionStatement));
        }
        if (PUNISHMENT.equals(authorityDomain)) {
            return castPunishmentProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::punishmentProjectionStatement));
        }
        if (ECONOMY.equals(authorityDomain)) {
            return castEconomyProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::economyProjectionStatement));
        }
        if (STATS.equals(authorityDomain)) {
            return castStatsProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::statsProjectionStatement));
        }
        return (command, decision) -> {
        };
    }

    @Override
    public AuthorityEmissionSink emissionSink(String authorityDomain) {
        KafkaClientBundle kafka = kafka(authorityDomain);
        AuthorityEmissionSink kafkaSink = new KafkaAuthorityEmissionSink(
                kafka.producer(),
                new KafkaAuthorityEmissionTopics(
                        eventTopic(authorityDomain),
                        stateTopic(authorityDomain),
                        responseTopic(authorityDomain)),
                KAFKA_SEND_TIMEOUT);
        AuthorityEmissionSink cacheSink = new ValkeyAuthorityCacheSink(clients.valkey().client());
        return emission -> {
            kafkaSink.publish(emission);
            cacheSink.publish(emission);
        };
    }

    @Override
    public <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> decisionRecorder(String authorityDomain) {
        if (PRESENCE.equals(authorityDomain)) {
            return castDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    PresenceAuthorityWireCodec::encodeDecisionPayload));
        }
        if (SUBJECT.equals(authorityDomain)) {
            return castSubjectDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    SubjectAuthorityWireCodec::encodeDecisionPayload));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castRouteDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    RouteAuthorityWireCodec::encodeDecisionPayload));
        }
        if (SESSION.equals(authorityDomain)) {
            return castSessionDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    SessionAuthorityWireCodec::encodeDecisionPayload));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castArtifactDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    ArtifactMetadataAuthorityWireCodec::encodeDecisionPayload));
        }
        if (PLAYER_PROFILE.equals(authorityDomain)) {
            return castPlayerProfileDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    StandardCapabilityAuthorityWireCodec::encodePlayerProfileDecisionPayload));
        }
        if (RANK.equals(authorityDomain)) {
            return castRankDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    StandardCapabilityAuthorityWireCodec::encodeRankDecisionPayload));
        }
        if (PUNISHMENT.equals(authorityDomain)) {
            return castPunishmentDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    StandardCapabilityAuthorityWireCodec::encodePunishmentDecisionPayload));
        }
        if (ECONOMY.equals(authorityDomain)) {
            return castEconomyDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    StandardCapabilityAuthorityWireCodec::encodeEconomyDecisionPayload));
        }
        if (STATS.equals(authorityDomain)) {
            return castStatsDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    StandardCapabilityAuthorityWireCodec::encodeStatsDecisionPayload));
        }
        return new JdbcAuthorityDecisionRecorder<>(
                clients.postgres().dataSource(),
                DECISION_RECORDER,
                ExternalAuthorityRuntimeBindings::decisionPayload);
    }

    @Override
    public AuthorityOffsetCommitter offsetCommitter(String authorityDomain) {
        return new KafkaAuthorityOffsetCommitter(kafka(authorityDomain).consumer());
    }

    @Override
    public <S, R> IdempotencyLedger<S, R> idempotencyLedger(String authorityDomain) {
        if (PRESENCE.equals(authorityDomain)) {
            return castLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    presenceStoredDecisionCodec()));
        }
        if (SUBJECT.equals(authorityDomain)) {
            return castSubjectLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    subjectStoredDecisionCodec()));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castRouteLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    routeStoredDecisionCodec()));
        }
        if (SESSION.equals(authorityDomain)) {
            return castSessionLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    sessionStoredDecisionCodec()));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castArtifactLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    artifactStoredDecisionCodec()));
        }
        if (PLAYER_PROFILE.equals(authorityDomain)) {
            return castPlayerProfileLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    playerProfileStoredDecisionCodec()));
        }
        if (RANK.equals(authorityDomain)) {
            return castRankLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    rankStoredDecisionCodec()));
        }
        if (PUNISHMENT.equals(authorityDomain)) {
            return castPunishmentLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    punishmentStoredDecisionCodec()));
        }
        if (ECONOMY.equals(authorityDomain)) {
            return castEconomyLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    economyStoredDecisionCodec()));
        }
        if (STATS.equals(authorityDomain)) {
            return castStatsLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    statsStoredDecisionCodec()));
        }
        return new ValkeyIdempotencyLedger<>(
                clients.valkey().client(),
                "authority:" + authorityDomain + ":idempotency",
                unsupportedStoredDecisionCodec(authorityDomain));
    }

    private KafkaClientBundle kafka(String authorityDomain) {
        return clients.kafka(authorityDomain);
    }

    @SuppressWarnings("unchecked")
    private static <C extends CommandPayload> KafkaAuthorityCommandDecoder<C> commandDecoder(String authorityDomain) {
        if (PRESENCE.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    PresenceAuthorityWireCodec.decodeCommand(record);
        }
        if (SUBJECT.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    SubjectAuthorityWireCodec.decodeCommand(record);
        }
        if (ROUTE.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    RouteAuthorityWireCodec.decodeCommand(record);
        }
        if (SESSION.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    SessionAuthorityWireCodec.decodeCommand(record);
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    ArtifactMetadataAuthorityWireCodec.decodeCommand(record);
        }
        if (PLAYER_PROFILE.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    StandardCapabilityAuthorityWireCodec.decodePlayerProfileCommand(record);
        }
        if (RANK.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    StandardCapabilityAuthorityWireCodec.decodeRankCommand(record);
        }
        if (PUNISHMENT.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    StandardCapabilityAuthorityWireCodec.decodePunishmentCommand(record);
        }
        if (ECONOMY.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    StandardCapabilityAuthorityWireCodec.decodeEconomyCommand(record);
        }
        if (STATS.equals(authorityDomain)) {
            return record -> (sh.harold.fulcrum.data.authority.AuthorityCommand<C>)
                    StandardCapabilityAuthorityWireCodec.decodeStatsCommand(record);
        }
        return record -> {
            throw new IllegalArgumentException("No external command decoder for authority domain " + authorityDomain);
        };
    }

    @SuppressWarnings("unchecked")
    private static <S> JdbcAuthorityStateCodec<S> stateCodec(String authorityDomain) {
        if (PRESENCE.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) presenceStateCodec();
        }
        if (SUBJECT.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) subjectStateCodec();
        }
        if (ROUTE.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) routeStateCodec();
        }
        if (SESSION.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) sessionStateCodec();
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) artifactStateCodec();
        }
        if (PLAYER_PROFILE.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) playerProfileStateCodec();
        }
        if (RANK.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) rankStateCodec();
        }
        if (PUNISHMENT.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) punishmentStateCodec();
        }
        if (ECONOMY.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) economyStateCodec();
        }
        if (STATS.equals(authorityDomain)) {
            return (JdbcAuthorityStateCodec<S>) statsStateCodec();
        }
        return unsupportedStateCodec(authorityDomain);
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castPresenceProjection(
            AuthorityProjectionWriter<PresenceState, PresenceCommand, PresenceReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castSubjectProjection(
            AuthorityProjectionWriter<SubjectState, SubjectCommand, SubjectReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castRouteProjection(
            AuthorityProjectionWriter<RouteState, RouteCommand, RouteReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castSessionProjection(
            AuthorityProjectionWriter<SessionState, SessionCommand, SessionReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castArtifactProjection(
            AuthorityProjectionWriter<ArtifactMetadataState, PublishArtifactMetadata, ArtifactMetadataReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castPlayerProfileProjection(
            AuthorityProjectionWriter<PlayerProfileState, UpsertPlayerProfile, PlayerProfileReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castRankProjection(
            AuthorityProjectionWriter<RankState, GrantRank, RankReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castPunishmentProjection(
            AuthorityProjectionWriter<PunishmentState, IssuePunishment, PunishmentReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castEconomyProjection(
            AuthorityProjectionWriter<EconomyState, PostLedgerEntry, EconomyReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castStatsProjection(
            AuthorityProjectionWriter<StatsState, RecordStatDelta, StatsReceipt> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castDecisionRecorder(
            AuthorityDecisionRecorder<PresenceState, PresenceCommand, PresenceReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castSubjectDecisionRecorder(
            AuthorityDecisionRecorder<SubjectState, SubjectCommand, SubjectReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castRouteDecisionRecorder(
            AuthorityDecisionRecorder<RouteState, RouteCommand, RouteReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castSessionDecisionRecorder(
            AuthorityDecisionRecorder<SessionState, SessionCommand, SessionReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castArtifactDecisionRecorder(
            AuthorityDecisionRecorder<ArtifactMetadataState, PublishArtifactMetadata, ArtifactMetadataReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castPlayerProfileDecisionRecorder(
            AuthorityDecisionRecorder<PlayerProfileState, UpsertPlayerProfile, PlayerProfileReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castRankDecisionRecorder(
            AuthorityDecisionRecorder<RankState, GrantRank, RankReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castPunishmentDecisionRecorder(
            AuthorityDecisionRecorder<PunishmentState, IssuePunishment, PunishmentReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castEconomyDecisionRecorder(
            AuthorityDecisionRecorder<EconomyState, PostLedgerEntry, EconomyReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castStatsDecisionRecorder(
            AuthorityDecisionRecorder<StatsState, RecordStatDelta, StatsReceipt> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castLedger(
            IdempotencyLedger<PresenceState, PresenceReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castSubjectLedger(
            IdempotencyLedger<SubjectState, SubjectReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castRouteLedger(
            IdempotencyLedger<RouteState, RouteReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castSessionLedger(
            IdempotencyLedger<SessionState, SessionReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castArtifactLedger(
            IdempotencyLedger<ArtifactMetadataState, ArtifactMetadataReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castPlayerProfileLedger(
            IdempotencyLedger<PlayerProfileState, PlayerProfileReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castRankLedger(
            IdempotencyLedger<RankState, RankReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castPunishmentLedger(
            IdempotencyLedger<PunishmentState, PunishmentReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castEconomyLedger(
            IdempotencyLedger<EconomyState, EconomyReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castStatsLedger(
            IdempotencyLedger<StatsState, StatsReceipt> ledger) {
        return (IdempotencyLedger<S, R>) ledger;
    }

    private static JdbcAuthorityStateCodec<PresenceState> presenceStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(PresenceState state) {
                return PresenceAuthorityWireCodec.encodeState(state);
            }

            @Override
            public PresenceState decode(String payload) {
                return PresenceAuthorityWireCodec.decodeState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<SubjectState> subjectStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(SubjectState state) {
                return SubjectAuthorityWireCodec.encodeState(state);
            }

            @Override
            public SubjectState decode(String payload) {
                return SubjectAuthorityWireCodec.decodeState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<RouteState> routeStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(RouteState state) {
                return RouteAuthorityWireCodec.encodeState(state);
            }

            @Override
            public RouteState decode(String payload) {
                return RouteAuthorityWireCodec.decodeState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<SessionState> sessionStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(SessionState state) {
                return SessionAuthorityWireCodec.encodeState(state);
            }

            @Override
            public SessionState decode(String payload) {
                return SessionAuthorityWireCodec.decodeState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<ArtifactMetadataState> artifactStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(ArtifactMetadataState state) {
                return ArtifactMetadataAuthorityWireCodec.encodeState(state);
            }

            @Override
            public ArtifactMetadataState decode(String payload) {
                return ArtifactMetadataAuthorityWireCodec.decodeState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<PlayerProfileState> playerProfileStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(PlayerProfileState state) {
                return StandardCapabilityAuthorityWireCodec.encodePlayerProfileState(state);
            }

            @Override
            public PlayerProfileState decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodePlayerProfileState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<RankState> rankStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(RankState state) {
                return StandardCapabilityAuthorityWireCodec.encodeRankState(state);
            }

            @Override
            public RankState decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeRankState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<PunishmentState> punishmentStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(PunishmentState state) {
                return StandardCapabilityAuthorityWireCodec.encodePunishmentState(state);
            }

            @Override
            public PunishmentState decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodePunishmentState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<EconomyState> economyStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(EconomyState state) {
                return StandardCapabilityAuthorityWireCodec.encodeEconomyState(state);
            }

            @Override
            public EconomyState decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeEconomyState(payload);
            }
        };
    }

    private static JdbcAuthorityStateCodec<StatsState> statsStateCodec() {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(StatsState state) {
                return StandardCapabilityAuthorityWireCodec.encodeStatsState(state);
            }

            @Override
            public StatsState decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeStatsState(payload);
            }
        };
    }

    private static <S> JdbcAuthorityStateCodec<S> unsupportedStateCodec(String authorityDomain) {
        return new JdbcAuthorityStateCodec<>() {
            @Override
            public String encode(S state) {
                throw new IllegalArgumentException("No external state codec for authority domain " + authorityDomain);
            }

            @Override
            public S decode(String payload) {
                throw new IllegalArgumentException("No external state codec for authority domain " + authorityDomain);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<PresenceState, PresenceReceipt> presenceStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<PresenceState, PresenceReceipt> decision) {
                return PresenceAuthorityWireCodec.encodeStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<PresenceState, PresenceReceipt> decode(String payload) {
                return PresenceAuthorityWireCodec.decodeStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<SubjectState, SubjectReceipt> subjectStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<SubjectState, SubjectReceipt> decision) {
                return SubjectAuthorityWireCodec.encodeStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<SubjectState, SubjectReceipt> decode(String payload) {
                return SubjectAuthorityWireCodec.decodeStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<RouteState, RouteReceipt> routeStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<RouteState, RouteReceipt> decision) {
                return RouteAuthorityWireCodec.encodeStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<RouteState, RouteReceipt> decode(String payload) {
                return RouteAuthorityWireCodec.decodeStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<SessionState, SessionReceipt> sessionStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<SessionState, SessionReceipt> decision) {
                return SessionAuthorityWireCodec.encodeStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<SessionState, SessionReceipt> decode(String payload) {
                return SessionAuthorityWireCodec.decodeStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<ArtifactMetadataState, ArtifactMetadataReceipt> artifactStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision) {
                return ArtifactMetadataAuthorityWireCodec.encodeStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decode(String payload) {
                return ArtifactMetadataAuthorityWireCodec.decodeStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<PlayerProfileState, PlayerProfileReceipt> playerProfileStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision) {
                return StandardCapabilityAuthorityWireCodec.encodePlayerProfileStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodePlayerProfileStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<RankState, RankReceipt> rankStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<RankState, RankReceipt> decision) {
                return StandardCapabilityAuthorityWireCodec.encodeRankStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<RankState, RankReceipt> decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeRankStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<PunishmentState, PunishmentReceipt> punishmentStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<PunishmentState, PunishmentReceipt> decision) {
                return StandardCapabilityAuthorityWireCodec.encodePunishmentStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<PunishmentState, PunishmentReceipt> decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodePunishmentStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<EconomyState, EconomyReceipt> economyStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<EconomyState, EconomyReceipt> decision) {
                return StandardCapabilityAuthorityWireCodec.encodeEconomyStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<EconomyState, EconomyReceipt> decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeEconomyStoredDecision(payload);
            }
        };
    }

    private static ValkeyStoredAuthorityDecisionCodec<StatsState, StatsReceipt> statsStoredDecisionCodec() {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<StatsState, StatsReceipt> decision) {
                return StandardCapabilityAuthorityWireCodec.encodeStatsStoredDecision(decision);
            }

            @Override
            public StoredAuthorityDecision<StatsState, StatsReceipt> decode(String payload) {
                return StandardCapabilityAuthorityWireCodec.decodeStatsStoredDecision(payload);
            }
        };
    }

    private static <S, R> ValkeyStoredAuthorityDecisionCodec<S, R> unsupportedStoredDecisionCodec(String authorityDomain) {
        return new ValkeyStoredAuthorityDecisionCodec<>() {
            @Override
            public String encode(StoredAuthorityDecision<S, R> decision) {
                throw new IllegalArgumentException("No external idempotency codec for authority domain " + authorityDomain);
            }

            @Override
            public StoredAuthorityDecision<S, R> decode(String payload) {
                throw new IllegalArgumentException("No external idempotency codec for authority domain " + authorityDomain);
            }
        };
    }

    private static SimpleStatement presenceProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<PresenceCommand> command,
            AuthorityDecision<PresenceState, PresenceReceipt> decision) {
        PresenceSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.presence_hot (
                    subject_id,
                    presence_id,
                    owner_instance_id,
                    owner_token,
                    owner_epoch,
                    lifecycle_status,
                    session_id,
                    route_id,
                    observed_at,
                    expires_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.subjectId().value().toString(),
                snapshot.presenceId().value(),
                snapshot.ownerInstanceId().value(),
                snapshot.ownerToken().value(),
                snapshot.ownerEpoch(),
                snapshot.status().name(),
                snapshot.sessionId().map(SessionId::value).orElse(""),
                snapshot.routeId().map(RouteId::value).orElse(""),
                snapshot.observedAt().toString(),
                snapshot.expiresAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement subjectProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<SubjectCommand> command,
            AuthorityDecision<SubjectState, SubjectReceipt> decision) {
        SubjectSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.subject_identity_hot (
                    identity_provider,
                    external_identity,
                    subject_id,
                    lifecycle_status,
                    registered_by,
                    registered_at,
                    retired_by,
                    retired_at,
                    retire_reason,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.identityProvider().name(),
                snapshot.externalIdentity().value(),
                snapshot.subjectId().value().toString(),
                snapshot.status().name(),
                snapshot.registeredBy().value(),
                snapshot.registeredAt().toString(),
                snapshot.retiredBy().map(value -> value.value()).orElse(""),
                snapshot.retiredAt().map(value -> value.toString()).orElse(""),
                snapshot.retireReason().map(Enum::name).orElse(""),
                decision.revision().value());
    }

    private static SimpleStatement routeProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<RouteCommand> command,
            AuthorityDecision<RouteState, RouteReceipt> decision) {
        RouteSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.route_hot (
                    route_id,
                    subject_id,
                    target_session_id,
                    target_instance_id,
                    lifecycle_status,
                    requested_at,
                    expires_at,
                    completed_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.routeId().value(),
                snapshot.subjectId().value().toString(),
                snapshot.targetSessionId().value(),
                snapshot.targetInstanceId().value(),
                snapshot.status().name(),
                snapshot.requestedAt().toString(),
                snapshot.expiresAt().toString(),
                snapshot.completedAt().map(value -> value.toString()).orElse(""),
                decision.revision().value());
    }

    private static SimpleStatement sessionProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<SessionCommand> command,
            AuthorityDecision<SessionState, SessionReceipt> decision) {
        SessionSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.session_hot (
                    session_id,
                    experience_id,
                    slot_id,
                    owner_instance_id,
                    owner_token,
                    owner_epoch,
                    resolved_manifest_id,
                    lifecycle_status,
                    opened_at,
                    lease_expires_at,
                    activated_at,
                    closed_at,
                    close_reason,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.sessionId().value(),
                snapshot.experienceId().value(),
                snapshot.slotId().value(),
                snapshot.ownerInstanceId().value(),
                snapshot.ownerToken().value(),
                snapshot.ownerEpoch(),
                snapshot.resolvedManifestId().value(),
                snapshot.status().name(),
                snapshot.openedAt().toString(),
                snapshot.leaseExpiresAt().toString(),
                snapshot.activatedAt().map(value -> value.toString()).orElse(""),
                snapshot.closedAt().map(value -> value.toString()).orElse(""),
                snapshot.closeReason().map(Enum::name).orElse(""),
                decision.revision().value());
    }

    private static SimpleStatement artifactProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<PublishArtifactMetadata> command,
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision) {
        ArtifactMetadata metadata = decision.state().metadata().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.artifact_metadata_hot (
                    digest_algorithm,
                    digest_value,
                    kind,
                    byte_length,
                    content_address,
                    producer_principal,
                    provenance,
                    published_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                metadata.digest().algorithm(),
                metadata.digest().value(),
                metadata.kind().name(),
                metadata.byteLength(),
                metadata.contentAddress().value(),
                metadata.producerPrincipal().value(),
                metadata.provenance().value(),
                metadata.publishedAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement playerProfileProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<UpsertPlayerProfile> command,
            AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision) {
        PlayerProfileSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.standard_player_profile_effective_hot (
                    subject_id,
                    display_name,
                    updated_by,
                    observed_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?)
                """,
                snapshot.subjectId().value().toString(),
                snapshot.displayName(),
                snapshot.updatedBy().value(),
                snapshot.observedAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement rankProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<GrantRank> command,
            AuthorityDecision<RankState, RankReceipt> decision) {
        EffectiveRankSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.standard_rank_effective_hot (
                    subject_id,
                    primary_rank_key,
                    permissions,
                    updated_by,
                    updated_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                snapshot.subjectId().value().toString(),
                snapshot.primaryRankKey(),
                snapshot.permissions(),
                snapshot.updatedBy().value(),
                snapshot.updatedAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement punishmentProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<IssuePunishment> command,
            AuthorityDecision<PunishmentState, PunishmentReceipt> decision) {
        ActivePunishmentSnapshot snapshot = decision.state().active().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.standard_punishment_active_hot (
                    subject_id,
                    punishment_id,
                    reason,
                    issued_by,
                    issued_at,
                    expires_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.subjectId().value().toString(),
                snapshot.punishmentId(),
                snapshot.reason(),
                snapshot.issuedBy().value(),
                snapshot.issuedAt().toString(),
                snapshot.expiresAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement economyProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<PostLedgerEntry> command,
            AuthorityDecision<EconomyState, EconomyReceipt> decision) {
        EconomyBalanceSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.standard_economy_balance_hot (
                    subject_id,
                    currency_key,
                    balance_minor_units,
                    last_entry_id,
                    updated_by,
                    updated_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.accountId().subjectId().value().toString(),
                snapshot.accountId().currencyKey(),
                snapshot.balanceMinorUnits(),
                snapshot.lastEntryId(),
                snapshot.updatedBy().value(),
                snapshot.updatedAt().toString(),
                decision.revision().value());
    }

    private static SimpleStatement statsProjectionStatement(
            sh.harold.fulcrum.data.authority.AuthorityCommand<RecordStatDelta> command,
            AuthorityDecision<StatsState, StatsReceipt> decision) {
        StatsCounterSnapshot snapshot = decision.state().current().orElseThrow();
        return SimpleStatement.newInstance("""
                INSERT INTO fulcrum.standard_stats_counter_hot (
                    subject_id,
                    stat_key,
                    total,
                    last_entry_id,
                    updated_by,
                    updated_at,
                    revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.counterId().subjectId().value().toString(),
                snapshot.counterId().statKey(),
                snapshot.total(),
                snapshot.lastEntryId(),
                snapshot.updatedBy().value(),
                snapshot.updatedAt().toString(),
                decision.revision().value());
    }

    private static String decisionPayload(AuthorityDecision<?, ?> decision) {
        return "status=" + decision.status().name()
                + "\nreason=" + decision.rejectionReason().map(Enum::name).orElse("")
                + "\nrevision=" + decision.revision().value()
                + "\nreplayed=" + decision.replayed()
                + "\nresponse=" + decision.response();
    }

    private static String commandTopic(String authorityDomain) {
        return "cmd." + authorityDomain;
    }

    private static String eventTopic(String authorityDomain) {
        return "evt." + authorityDomain;
    }

    private static String stateTopic(String authorityDomain) {
        return "state." + authorityDomain;
    }

    private static String responseTopic(String authorityDomain) {
        return "rsp." + authorityDomain;
    }
}
