package sh.harold.fulcrum.validation.auctionescrow;

import com.datastax.oss.driver.api.core.CqlSession;
import io.valkey.HostAndPort;
import io.valkey.UnifiedJedis;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionTopics;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowStoreBackedRuntimeTest {
    private static final String COMMAND_TOPIC = "cmd.auction.escrow.store-backed";
    private static final String EVENT_TOPIC = "evt.auction.escrow.store-backed";
    private static final String STATE_TOPIC = "state.auction.escrow.store-backed";
    private static final String RESPONSE_TOPIC = "rsp.auction.escrow.store-backed";
    private static final String GROUP_ID = "auction-escrow-store-backed";
    private static final String RUNNER_COMMAND_TOPIC = "cmd.auction.escrow.runner";
    private static final String RUNNER_EVENT_TOPIC = "evt.auction.escrow.runner";
    private static final String RUNNER_STATE_TOPIC = "state.auction.escrow.runner";
    private static final String RUNNER_RESPONSE_TOPIC = "rsp.auction.escrow.runner";
    private static final String RUNNER_GROUP_ID = "auction-escrow-runner";
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-auction-escrow-backend");

    @TempDir
    private Path tempDir;

    @Test
    void backendRunnerPublishesReadinessFromStoreBackedBootApply() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(
                    stack.kafkaBootstrapServers(),
                    RUNNER_COMMAND_TOPIC,
                    RUNNER_EVENT_TOPIC,
                    RUNNER_STATE_TOPIC,
                    RUNNER_RESPONSE_TOPIC);
            AuctionEscrowBackendConfig config = config(stack, tempDir.resolve("auction-escrow.ready"));
            AuthorityBackendRegistrationReceipt registration = new CapabilityBackendRegistrationController()
                    .register(config.registrationRequest(NOW));

            try (AuctionEscrowBackendRunner runner = AuctionEscrowBackendRunner.open(config, registration)) {
                AuctionEscrowReadinessEvidence evidence = runner.publishReadiness(
                        Path.of(config.readyFile()),
                        NOW,
                        "store-backed-runner-boot");

                String document = Files.readString(Path.of(config.readyFile()));
                assertTrue(document.contains("status=ready"));
                assertTrue(document.contains("appliedOffsetSource=" + RUNNER_COMMAND_TOPIC));
                assertTrue(document.contains("runtimeStatus=ACCEPTED"));
                assertTrue(document.contains("replayed=false"));
                assertEquals("1", stack.queryPostgresScalar(
                        "SELECT revision FROM auction_escrow_authority_records WHERE aggregate_id = '"
                                + evidence.aggregateId() + "';"));
                assertEquals("ACCEPTED", stack.queryPostgresScalar(
                        "SELECT status FROM auction_escrow_authority_decisions WHERE aggregate_id = '"
                                + evidence.aggregateId() + "';"));
                assertRunnerCassandraProjection(stack, evidence.aggregateId());
                assertFalse(stack.getValkey(AuctionEscrowStoreBackedRuntime.IDEMPOTENCY_PREFIX
                        + ":boot-readiness:"
                        + sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests.sha256Hex("store-backed-runner-boot"))
                        .isBlank());
            }
        }
    }

    @Test
    void workerStopStartResumesEscrowSettlementThroughRealStoreAdapters() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchemas(stack);
            AuthorityBackendRegistrationReceipt registration = register();

            send(stack.kafkaBootstrapServers(), command(
                    "command-open-store",
                    "idem-open-store",
                    new OpenEscrow("auction-store-backed", "seller", "beacon", "COIN", NOW),
                    registration.fencingEpoch(),
                    0));
            send(stack.kafkaBootstrapServers(), command(
                    "command-hold-low-store",
                    "idem-hold-low-store",
                    new PlaceHold("auction-store-backed", "bidder-low", 100, "COIN", NOW.plusSeconds(1)),
                    registration.fencingEpoch(),
                    1));
            send(stack.kafkaBootstrapServers(), command(
                    "command-hold-high-store",
                    "idem-hold-high-store",
                    new PlaceHold("auction-store-backed", "bidder-high", 150, "COIN", NOW.plusSeconds(2)),
                    registration.fencingEpoch(),
                    2));

            try (StoreClients first = StoreClients.open(stack, "first")) {
                first.subscribe();
                assertAccepted(first.handleNext(registration));
                assertAccepted(first.handleNext(registration));
                assertAccepted(first.handleNext(registration));
            }

            send(stack.kafkaBootstrapServers(), command(
                    "command-settle-store",
                    "idem-settle-store",
                    new SettleEscrow("auction-store-backed", NOW.plusSeconds(3)),
                    registration.fencingEpoch(),
                    3));
            send(stack.kafkaBootstrapServers(), command(
                    "command-settle-store",
                    "idem-settle-store",
                    new SettleEscrow("auction-store-backed", NOW.plusSeconds(3)),
                    registration.fencingEpoch(),
                    3));

            try (StoreClients second = StoreClients.open(stack, "second")) {
                second.subscribe();
                AuthorityRuntimeReceipt settled = assertAccepted(second.handleNext(registration));
                AuthorityRuntimeReceipt replay = second.handleNext(registration).orElseThrow();

                assertFalse(settled.replayed());
                assertTrue(replay.replayed());
                assertEquals(new Revision(4), settled.revision());
            }

            assertEquals("4", stack.queryPostgresScalar(
                    "SELECT revision FROM auction_escrow_authority_records WHERE aggregate_id = 'escrow:auction-store-backed';"));
            assertEquals("1", stack.queryPostgresScalar(
                    "SELECT COUNT(*) FROM auction_escrow_authority_decisions WHERE command_id = 'command-settle-store';"));
            assertEquals("ACCEPTED", stack.queryPostgresScalar(
                    "SELECT status FROM auction_escrow_authority_decisions WHERE command_id = 'command-settle-store';"));
            assertCassandraProjection(stack);
            String storedDecision = stack.getValkey(AuctionEscrowStoreBackedRuntime.IDEMPOTENCY_PREFIX + ":idem-settle-store");
            assertFalse(storedDecision.isBlank(), "Valkey idempotency ledger must retain the settle decision");

            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 4);
            long settleResponses = responses.stream()
                    .filter(value -> value.contains("auctionId=auction-store-backed"))
                    .filter(value -> value.contains("escrowStatus=SETTLED"))
                    .count();
            assertEquals(1, settleResponses, "duplicate settle must not emit a second terminal response");
        }
    }

    @Test
    void higherRegistrationEpochTakesOverExistingEscrowRecord() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchemas(stack);
            AuthorityBackendRegistrationReceipt firstRegistration = register();
            AuthorityBackendRegistrationReceipt successorRegistration = registrationWithEpoch(
                    firstRegistration,
                    firstRegistration.fencingEpoch() + 1);

            send(stack.kafkaBootstrapServers(), command(
                    "command-open-epoch-takeover",
                    "idem-open-epoch-takeover",
                    new OpenEscrow("auction-epoch-takeover", "seller", "beacon", "COIN", NOW),
                    firstRegistration.fencingEpoch(),
                    0));

            try (StoreClients first = StoreClients.open(stack, "epoch-takeover-first")) {
                first.subscribe();
                assertAccepted(first.handleNext(firstRegistration));
            }

            assertEquals(Long.toString(firstRegistration.fencingEpoch()), stack.queryPostgresScalar(
                    "SELECT fencing_epoch FROM auction_escrow_authority_records WHERE aggregate_id = 'escrow:auction-epoch-takeover';"));
            try (PostgresClientHandle postgres = PostgresClientHandle.create(
                    stack.postgresJdbcUrl(),
                    stack.postgresUsername(),
                    stack.postgresPassword())) {
                assertEquals(successorRegistration.fencingEpoch(), AuctionEscrowStoreBackedRuntime.effectiveFencingEpoch(
                        postgres.dataSource(),
                        firstRegistration.fencingEpoch()));
            }

            send(stack.kafkaBootstrapServers(), command(
                    "command-hold-epoch-takeover",
                    "idem-hold-epoch-takeover",
                    new PlaceHold("auction-epoch-takeover", "bidder-successor", 125, "COIN", NOW.plusSeconds(1)),
                    successorRegistration.fencingEpoch(),
                    1));

            try (StoreClients successor = StoreClients.open(stack, "epoch-takeover-successor")) {
                successor.subscribe();
                AuthorityRuntimeReceipt takeover = assertAccepted(successor.handleNext(successorRegistration));
                assertEquals(new Revision(2), takeover.revision());
            }

            assertEquals(Long.toString(successorRegistration.fencingEpoch()), stack.queryPostgresScalar(
                    "SELECT fencing_epoch FROM auction_escrow_authority_records WHERE aggregate_id = 'escrow:auction-epoch-takeover';"));
            assertEquals("ACCEPTED", stack.queryPostgresScalar(
                    "SELECT status FROM auction_escrow_authority_decisions WHERE command_id = 'command-hold-epoch-takeover';"));

            send(stack.kafkaBootstrapServers(), command(
                    "command-stale-epoch-takeover",
                    "idem-stale-epoch-takeover",
                    new PlaceHold("auction-epoch-takeover", "bidder-stale", 130, "COIN", NOW.plusSeconds(2)),
                    firstRegistration.fencingEpoch(),
                    2));

            try (StoreClients stale = StoreClients.open(stack, "epoch-takeover-stale")) {
                stale.subscribe();
                AuthorityRuntimeReceipt staleReceipt = stale.handleNext(firstRegistration).orElseThrow();
                assertEquals(AuthorityDecisionStatus.REJECTED, staleReceipt.status());
                assertEquals(new Revision(2), staleReceipt.revision());
            }

            assertEquals("STALE_FENCING_EPOCH", stack.queryPostgresScalar(
                    "SELECT rejection_reason FROM auction_escrow_authority_decisions WHERE command_id = 'command-stale-epoch-takeover';"));
            assertEquals(Long.toString(successorRegistration.fencingEpoch()), stack.queryPostgresScalar(
                    "SELECT fencing_epoch FROM auction_escrow_authority_records WHERE aggregate_id = 'escrow:auction-epoch-takeover';"));
        }
    }

    @Test
    void liveStoreClientAppendsCommandsAndObservesDurableReplayEvidence() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchemas(stack);
            AuthorityBackendRegistrationReceipt registration = register();
            try (AuctionEscrowLiveStoreClient client = AuctionEscrowLiveStoreClient.open(liveStoreConfig(stack))) {
                client.append(command(
                        "command-open-live-client",
                        "idem-open-live-client",
                        new OpenEscrow("auction-live-client", "seller", "beacon", "COIN", NOW),
                        registration.fencingEpoch(),
                        0));
                client.append(command(
                        "command-hold-low-live-client",
                        "idem-hold-low-live-client",
                        new PlaceHold("auction-live-client", "bidder-low", 100, "COIN", NOW.plusSeconds(1)),
                        registration.fencingEpoch(),
                        1));
                client.append(command(
                        "command-hold-high-live-client",
                        "idem-hold-high-live-client",
                        new PlaceHold("auction-live-client", "bidder-high", 175, "COIN", NOW.plusSeconds(2)),
                        registration.fencingEpoch(),
                        2));
                client.append(command(
                        "command-settle-live-client",
                        "idem-settle-live-client",
                        new SettleEscrow("auction-live-client", NOW.plusSeconds(3)),
                        registration.fencingEpoch(),
                        3));

                try (StoreClients worker = StoreClients.open(stack, "live-client")) {
                    worker.subscribe();
                    assertAccepted(worker.handleNext(registration));
                    assertAccepted(worker.handleNext(registration));
                    assertAccepted(worker.handleNext(registration));
                    assertAccepted(worker.handleNext(registration));
                    client.append(command(
                            "command-settle-live-client-replay",
                            "idem-settle-live-client",
                            new SettleEscrow("auction-live-client", NOW.plusSeconds(3)),
                            registration.fencingEpoch(),
                            4));
                    AuthorityRuntimeReceipt replay = assertAccepted(worker.handleNext(registration));
                    assertTrue(replay.replayed());
                }

                AuctionEscrowLiveStoreClient.ResponseObservation response =
                        client.awaitResponse("command-settle-live-client", Duration.ofSeconds(10));
                assertEquals("SETTLED", response.fields().get("escrowStatus"));
                AuctionEscrowLiveStoreClient.PostgresDecisionObservation settled =
                        client.awaitDecision("command-settle-live-client", Duration.ofSeconds(10));
                AuctionEscrowLiveStoreClient.PostgresDecisionObservation replayed =
                        client.awaitDecision("command-settle-live-client-replay", Duration.ofSeconds(10));
                AuctionEscrowLiveStoreClient.PostgresRecordObservation record =
                        client.awaitRecord("escrow:auction-live-client", 4, Duration.ofSeconds(10));
                AuctionEscrowLiveStoreClient.CassandraProjectionObservation projection =
                        client.awaitProjection("escrow:auction-live-client", EscrowStatus.SETTLED, 4, Duration.ofSeconds(10));
                AuctionEscrowLiveStoreClient.ValkeyObservation idempotency =
                        client.awaitIdempotency("idem-settle-live-client", Duration.ofSeconds(10));

                assertEquals("ACCEPTED", settled.status());
                assertFalse(settled.replayed());
                assertEquals("ACCEPTED", replayed.status());
                assertTrue(replayed.replayed());
                assertEquals(4, record.revision());
                assertEquals("SETTLED", record.stateFields().get("status"));
                assertEquals(275, projection.totalHeldMinor());
                assertEquals(275, projection.totalReleasedMinor());
                assertFalse(idempotency.payload().isBlank());
                client.pollResponses(Duration.ofMillis(500));
                assertEquals(1, client.observedResponses().stream()
                        .filter(observation -> "auction-live-client".equals(observation.fields().get("auctionId")))
                        .filter(observation -> "SETTLED".equals(observation.fields().get("escrowStatus")))
                        .count());
            }
        }
    }

    private static AuthorityRuntimeReceipt assertAccepted(Optional<AuthorityRuntimeReceipt> maybeReceipt) {
        AuthorityRuntimeReceipt receipt = maybeReceipt.orElseThrow();
        assertEquals(AuthorityDecisionStatus.ACCEPTED, receipt.status());
        return receipt;
    }

    private static AuthorityBackendRegistrationReceipt register() {
        return new CapabilityBackendRegistrationController().register(AuthorityBackendRegistrationRequest.credentialed(
                AuctionEscrowAuthority.descriptor(),
                new HostSecurityContext(
                        new HostInstanceIdentity(
                                new InstanceId("instance-auction-escrow-store-backed"),
                                "authority-backend",
                                new PoolId("pool-authority"),
                                new MachineRef("machine-authority"),
                                PRINCIPAL),
                        "service-account:auction-escrow-store-backed",
                        HostCredentialScope.of(
                                AuthorityBackendGrants.authorityDomain(AuctionEscrowAuthority.AUTHORITY_DOMAIN),
                                AuthorityBackendGrants.resourceClass(AuctionEscrowAuthority.RESOURCE_CLASS))),
                "sha256:auction-escrow-store-backed",
                NOW));
    }

    private static AuthorityBackendRegistrationReceipt registrationWithEpoch(
            AuthorityBackendRegistrationReceipt receipt,
            long fencingEpoch) {
        return new AuthorityBackendRegistrationReceipt(
                receipt.status(),
                receipt.capabilityId(),
                receipt.descriptorDigest(),
                receipt.bundleDigest(),
                receipt.materializationPlanHash(),
                receipt.principalId(),
                receipt.grantFingerprint(),
                fencingEpoch,
                receipt.issuedAt().plusSeconds(1),
                receipt.receiptId() + "-epoch-" + fencingEpoch,
                receipt.rejectionReason(),
                receipt.signature() + "-epoch-" + fencingEpoch);
    }

    private static AuthorityCommand<AuctionEscrowCommand> command(
            String commandId,
            String idempotencyKey,
            AuctionEscrowCommand payload,
            long fencingEpoch,
            long expectedRevision) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        AuctionEscrowAuthority.aggregateId(payload.auctionId()),
                        AuctionEscrowAuthority.CONTRACT,
                        AuctionEscrowContract.commandName(payload),
                        trace(commandId),
                        Optional.empty(),
                        payload),
                PRINCIPAL,
                fencingEpoch,
                Optional.of(new Revision(expectedRevision)),
                AuctionEscrowContract.payloadFingerprint(payload, idempotencyKey),
                NOW);
    }

    private static TraceEnvelope trace(String spanId) {
        return new TraceEnvelope(
                "trace-auction-escrow-store-backed",
                spanId,
                Optional.empty(),
                NOW,
                "auction-escrow-store-backed-test",
                new InstanceId("instance-auction-escrow-store-backed-test"));
    }

    private static void send(String bootstrapServers, AuthorityCommand<AuctionEscrowCommand> command) throws Exception {
        try (KafkaProducer<String, String> producer = producer(bootstrapServers)) {
            producer.send(new ProducerRecord<>(
                    COMMAND_TOPIC,
                    command.envelope().aggregateId().value(),
                    AuctionEscrowCommandWireCodec.encode(command))).get(20, TimeUnit.SECONDS);
        }
    }

    private static void createTopics(String bootstrapServers) throws Exception {
        createTopics(bootstrapServers, COMMAND_TOPIC, EVENT_TOPIC, STATE_TOPIC, RESPONSE_TOPIC);
    }

    private static void createTopics(
            String bootstrapServers,
            String commandTopic,
            String eventTopic,
            String stateTopic,
            String responseTopic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(
                    new NewTopic(commandTopic, 1, (short) 1),
                    new NewTopic(eventTopic, 1, (short) 1),
                    new NewTopic(stateTopic, 1, (short) 1),
                    new NewTopic(responseTopic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }

    private static void createSchemas(FulcrumSubstrateStack stack) {
        stack.executePostgres("""
                CREATE TABLE auction_escrow_authority_records (
                    aggregate_id TEXT PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    fencing_epoch BIGINT NOT NULL,
                    state_payload TEXT NOT NULL
                );
                CREATE TABLE auction_escrow_authority_decisions (
                    command_id TEXT PRIMARY KEY,
                    aggregate_id TEXT NOT NULL,
                    source_topic TEXT NOT NULL,
                    source_partition INTEGER NOT NULL,
                    source_offset BIGINT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    revision BIGINT NOT NULL,
                    replayed BOOLEAN NOT NULL,
                    trace_id TEXT NOT NULL,
                    decision_payload TEXT NOT NULL
                );
                """);
        stack.executeCassandra("""
                CREATE KEYSPACE IF NOT EXISTS fulcrum
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

                CREATE TABLE IF NOT EXISTS fulcrum.auction_escrow_projection (
                    aggregate_id text PRIMARY KEY,
                    auction_id text,
                    status text,
                    total_held_minor bigint,
                    total_released_minor bigint,
                    revision bigint
                );
                """);
    }

    private static void assertCassandraProjection(FulcrumSubstrateStack stack) {
        String output = stack.queryCassandra("""
                SELECT aggregate_id, auction_id, status, total_held_minor, total_released_minor, revision
                FROM fulcrum.auction_escrow_projection
                WHERE aggregate_id = 'escrow:auction-store-backed';
                """);
        assertTrue(output.contains("escrow:auction-store-backed"));
        assertTrue(output.contains("auction-store-backed"));
        assertTrue(output.contains("SETTLED"));
        assertTrue(output.contains("250"));
        assertTrue(output.contains("4"));
    }

    private static void assertRunnerCassandraProjection(FulcrumSubstrateStack stack, String aggregateId) {
        String output = stack.queryCassandra("""
                SELECT aggregate_id, status, revision
                FROM fulcrum.auction_escrow_projection
                WHERE aggregate_id = '%s';
                """.formatted(aggregateId));
        assertTrue(output.contains(aggregateId));
        assertTrue(output.contains("OPEN"));
        assertTrue(output.contains("1"));
    }

    private static AuctionEscrowBackendConfig config(FulcrumSubstrateStack stack, Path readyFile) {
        Map<String, String> environment = new HashMap<>();
        environment.put("FULCRUM_INSTANCE_ID", "fulcrum-auction-escrow-runner");
        environment.put("FULCRUM_INSTANCE_KIND", "authority-backend");
        environment.put("FULCRUM_POOL_ID", "pool-auction-escrow");
        environment.put("FULCRUM_MACHINE_REF", "node-a");
        environment.put("FULCRUM_PRINCIPAL_ID", PRINCIPAL.value());
        environment.put("FULCRUM_CREDENTIAL_REF", "secret://fulcrum-auction-escrow-identity/credential");
        environment.put("FULCRUM_ESCROW_BUNDLE_DIGEST", "sha256:auction-escrow-backend-dev");
        environment.put("FULCRUM_AUTHORITY_DOMAIN", "auction-escrow");
        environment.put("FULCRUM_AUTHORITY_RESOURCE_CLASS", AuctionEscrowAuthority.RESOURCE_CLASS);
        environment.put("FULCRUM_ESCROW_CONTRACT_NAME", AuctionEscrowAuthority.CONTRACT.value());
        environment.put("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", stack.kafkaBootstrapServers());
        environment.put("FULCRUM_ESCROW_COMMAND_TOPIC", RUNNER_COMMAND_TOPIC);
        environment.put("FULCRUM_ESCROW_EVENT_TOPIC", RUNNER_EVENT_TOPIC);
        environment.put("FULCRUM_ESCROW_STATE_TOPIC", RUNNER_STATE_TOPIC);
        environment.put("FULCRUM_ESCROW_RESPONSE_TOPIC", RUNNER_RESPONSE_TOPIC);
        environment.put("FULCRUM_ESCROW_CONSUMER_GROUP", RUNNER_GROUP_ID);
        environment.put("FULCRUM_POSTGRES_JDBC_URL", stack.postgresJdbcUrl());
        environment.put("FULCRUM_POSTGRES_USERNAME", stack.postgresUsername());
        environment.put("FULCRUM_POSTGRES_PASSWORD", stack.postgresPassword());
        environment.put("FULCRUM_CASSANDRA_CONTACT_POINTS", stack.cassandraHost() + ":" + stack.cassandraPort());
        environment.put("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1");
        environment.put("FULCRUM_VALKEY_ENDPOINT", stack.valkeyHost() + ":" + stack.valkeyPort());
        environment.put("FULCRUM_ESCROW_REPLAY_WATERMARK", "0");
        environment.put("FULCRUM_ESCROW_READY_FILE", readyFile.toString());
        environment.put("FULCRUM_ESCROW_STARTUP_MODE", "serve");
        return AuctionEscrowBackendConfig.from(environment);
    }

    private static AuctionEscrowLiveStoreClient.Config liveStoreConfig(FulcrumSubstrateStack stack) {
        return new AuctionEscrowLiveStoreClient.Config(
                stack.kafkaBootstrapServers(),
                COMMAND_TOPIC,
                RESPONSE_TOPIC,
                "auction-escrow-live-client",
                "auction-escrow-live-client-" + UUID.randomUUID(),
                stack.postgresJdbcUrl(),
                stack.postgresUsername(),
                stack.postgresPassword(),
                stack.cassandraHost() + ":" + stack.cassandraPort(),
                "datacenter1",
                stack.valkeyHost() + ":" + stack.valkeyPort(),
                Duration.ofSeconds(10));
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-auction-escrow-drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
            }
            return values;
        }
    }

    private static KafkaProducer<String, String> producer(String bootstrapServers) {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    private static final class StoreClients implements AutoCloseable {
        private final KafkaConsumer<String, String> consumer;
        private final KafkaProducer<String, String> producer;
        private final CqlSession cqlSession;
        private final UnifiedJedis valkey;
        private final PostgresClientHandle postgres;
        private AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker;

        private StoreClients(
                KafkaConsumer<String, String> consumer,
                KafkaProducer<String, String> producer,
                CqlSession cqlSession,
                UnifiedJedis valkey,
                PostgresClientHandle postgres) {
            this.consumer = consumer;
            this.producer = producer;
            this.cqlSession = cqlSession;
            this.valkey = valkey;
            this.postgres = postgres;
        }

        static StoreClients open(FulcrumSubstrateStack stack, String incarnation) {
            return new StoreClients(
                    consumer(stack.kafkaBootstrapServers(), GROUP_ID),
                    producer(stack.kafkaBootstrapServers()),
                    CqlSession.builder()
                            .addContactPoint(new InetSocketAddress(stack.cassandraHost(), stack.cassandraPort()))
                            .withLocalDatacenter("datacenter1")
                            .build(),
                    new UnifiedJedis(new HostAndPort(stack.valkeyHost(), stack.valkeyPort())),
                    PostgresClientHandle.create(stack.postgresJdbcUrl(), stack.postgresUsername(), stack.postgresPassword()));
        }

        void subscribe() {
            consumer.subscribe(List.of(COMMAND_TOPIC));
        }

        Optional<AuthorityRuntimeReceipt> handleNext(AuthorityBackendRegistrationReceipt registration) {
            if (worker == null) {
                worker = AuctionEscrowStoreBackedRuntime.worker(
                        consumer,
                        producer,
                        cqlSession,
                        valkey,
                        postgres.dataSource(),
                        registration.fencingEpoch(),
                        new KafkaAuthorityEmissionTopics(EVENT_TOPIC, STATE_TOPIC, RESPONSE_TOPIC));
            }
            return AuthorityBackendRuntimeGuard.guard(
                    registration,
                    worker)
                    .handleNext();
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            List<AutoCloseable> closeables = List.of(consumer, producer, cqlSession, valkey, postgres);
            for (AutoCloseable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = new IllegalStateException("failed to close store-backed escrow client", exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static KafkaConsumer<String, String> consumer(String bootstrapServers, String groupId) {
            return new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG, groupId,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        }
    }
}
