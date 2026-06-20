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
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectReceipt;
import sh.harold.fulcrum.data.subject.SubjectSnapshot;
import sh.harold.fulcrum.data.subject.SubjectState;

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
            return castProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::presenceProjectionStatement));
        }
        if (SUBJECT.equals(authorityDomain)) {
            return castProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::subjectProjectionStatement));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::routeProjectionStatement));
        }
        if (SESSION.equals(authorityDomain)) {
            return castProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::sessionProjectionStatement));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castProjection(new CassandraAuthorityProjectionWriter<>(
                    clients.cassandra().session(),
                    ExternalAuthorityRuntimeBindings::artifactProjectionStatement));
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
            return castDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    SubjectAuthorityWireCodec::encodeDecisionPayload));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    RouteAuthorityWireCodec::encodeDecisionPayload));
        }
        if (SESSION.equals(authorityDomain)) {
            return castDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    SessionAuthorityWireCodec::encodeDecisionPayload));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castDecisionRecorder(new JdbcAuthorityDecisionRecorder<>(
                    clients.postgres().dataSource(),
                    DECISION_RECORDER,
                    ArtifactMetadataAuthorityWireCodec::encodeDecisionPayload));
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
            return castLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    subjectStoredDecisionCodec()));
        }
        if (ROUTE.equals(authorityDomain)) {
            return castLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    routeStoredDecisionCodec()));
        }
        if (SESSION.equals(authorityDomain)) {
            return castLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    sessionStoredDecisionCodec()));
        }
        if (ARTIFACT_METADATA.equals(authorityDomain)) {
            return castLedger(new ValkeyIdempotencyLedger<>(
                    clients.valkey().client(),
                    "authority:" + authorityDomain + ":idempotency",
                    artifactStoredDecisionCodec()));
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
        return unsupportedStateCodec(authorityDomain);
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityProjectionWriter<S, C, R> castProjection(
            AuthorityProjectionWriter<?, ?, ?> writer) {
        return (AuthorityProjectionWriter<S, C, R>) writer;
    }

    @SuppressWarnings("unchecked")
    private static <S, C extends CommandPayload, R> AuthorityDecisionRecorder<S, C, R> castDecisionRecorder(
            AuthorityDecisionRecorder<?, ?, ?> recorder) {
        return (AuthorityDecisionRecorder<S, C, R>) recorder;
    }

    @SuppressWarnings("unchecked")
    private static <S, R> IdempotencyLedger<S, R> castLedger(IdempotencyLedger<?, ?> ledger) {
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
