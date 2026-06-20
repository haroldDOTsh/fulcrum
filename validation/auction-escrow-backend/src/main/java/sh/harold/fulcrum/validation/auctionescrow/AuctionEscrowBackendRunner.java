package sh.harold.fulcrum.validation.auctionescrow;

import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.store.cassandra.CassandraClientHandle;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionTopics;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;
import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;
import sh.harold.fulcrum.sdk.authority.GuardedAuthorityRuntimeWorker;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AuctionEscrowBackendRunner implements AutoCloseable {
    private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration BOOT_COMMAND_SEND_TIMEOUT = Duration.ofSeconds(20);

    private final AuctionEscrowBackendConfig config;
    private final AuthorityBackendRegistrationReceipt registrationReceipt;
    private final KafkaClientBundle kafka;
    private final PostgresClientHandle postgres;
    private final CassandraClientHandle cassandra;
    private final ValkeyClientHandle valkey;
    private final GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker;
    private final long runtimeFencingEpoch;
    private long acceptedApplyCount;

    private AuctionEscrowBackendRunner(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt registrationReceipt,
            KafkaClientBundle kafka,
            PostgresClientHandle postgres,
            CassandraClientHandle cassandra,
            ValkeyClientHandle valkey,
            GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker,
            long runtimeFencingEpoch) {
        this.config = Objects.requireNonNull(config, "config");
        this.registrationReceipt = Objects.requireNonNull(registrationReceipt, "registrationReceipt");
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.postgres = Objects.requireNonNull(postgres, "postgres");
        this.cassandra = Objects.requireNonNull(cassandra, "cassandra");
        this.valkey = Objects.requireNonNull(valkey, "valkey");
        this.worker = Objects.requireNonNull(worker, "worker");
        if (runtimeFencingEpoch <= 0) {
            throw new IllegalArgumentException("runtimeFencingEpoch must be positive");
        }
        this.runtimeFencingEpoch = runtimeFencingEpoch;
    }

    static AuctionEscrowBackendRunner open(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt registrationReceipt) {
        Objects.requireNonNull(config, "config");
        AuthorityBackendRegistrationReceipt admitted = AuthorityBackendRuntimeGuard.requireAdmitted(registrationReceipt);
        KafkaClientBundle kafka = KafkaClientBundle.create(
                config.kafkaBootstrapServers(),
                clientId(config),
                config.consumerGroup());
        PostgresClientHandle postgres = PostgresClientHandle.create(
                config.postgresJdbcUrl(),
                config.postgresUsername(),
                config.postgresPassword());
        CassandraClientHandle cassandra = CassandraClientHandle.createLazy(
                cassandraContactPoints(config.cassandraContactPoints()),
                config.cassandraLocalDatacenter());
        ValkeyClientHandle valkey = valkey(config.valkeyEndpoint());
        try {
            AuctionEscrowStoreSchemaProvisioner.Result schema = AuctionEscrowStoreSchemaProvisioner.provision(
                    postgres,
                    cassandra);
            System.out.println("auction-escrow-backend schemaReady=true"
                    + "|postgresStatements=" + schema.postgresStatementCount()
                    + "|cassandraStatements=" + schema.cassandraStatementCount());
            long storedFencingEpoch = AuctionEscrowStoreBackedRuntime.maxFencingEpoch(postgres.dataSource());
            long runtimeFencingEpoch = AuctionEscrowStoreBackedRuntime.effectiveFencingEpoch(
                    storedFencingEpoch,
                    admitted.fencingEpoch());
            System.out.println("auction-escrow-backend fencingEpoch"
                    + "|registration=" + admitted.fencingEpoch()
                    + "|storedMax=" + storedFencingEpoch
                    + "|runtime=" + runtimeFencingEpoch);
            kafka.subscribe(List.of(config.commandTopic()));
            AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> runtimeWorker =
                    AuctionEscrowStoreBackedRuntime.worker(
                            kafka.consumer(),
                            kafka.producer(),
                            cassandra.session(),
                            valkey.client(),
                            postgres.dataSource(),
                            runtimeFencingEpoch,
                            new KafkaAuthorityEmissionTopics(
                                    config.eventTopic(),
                                    config.stateTopic(),
                                    config.responseTopic()));
            return new AuctionEscrowBackendRunner(
                    config,
                    admitted,
                    kafka,
                    postgres,
                    cassandra,
                    valkey,
                    AuthorityBackendRuntimeGuard.guard(admitted, runtimeWorker),
                    runtimeFencingEpoch);
        } catch (RuntimeException exception) {
            closeAll(kafka, cassandra, valkey, postgres);
            throw exception;
        }
    }

    AuctionEscrowReadinessEvidence publishReadiness(Path readyFile, Instant generatedAt, String bootNonce) {
        AuthorityCommand<AuctionEscrowCommand> bootCommand = AuctionEscrowBootReadiness.bootCommand(
                config,
                runtimeFencingEpoch,
                generatedAt,
                bootNonce);
        sendBootCommand(bootCommand);
        AuctionEscrowReadinessEvidence evidence = AuctionEscrowReadinessEvidence.from(
                config,
                registrationReceipt,
                runtimeFencingEpoch,
                awaitBootApply(bootCommand.envelope().aggregateId()),
                bootNonce,
                acceptedApplyCount,
                generatedAt);
        try {
            AuctionEscrowReadinessPublisher.publish(readyFile, evidence);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to publish escrow readiness evidence", exception);
        }
        return evidence;
    }

    void serveForever() {
        while (!Thread.currentThread().isInterrupted()) {
            Optional<AuthorityRuntimeReceipt> receipt = worker.handleNext();
            receipt.ifPresent(this::observe);
        }
    }

    @Override
    public void close() {
        closeAll(kafka, cassandra, valkey, postgres);
    }

    private AuthorityRuntimeReceipt awaitBootApply(AggregateId bootAggregateId) {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            Optional<AuthorityRuntimeReceipt> maybeReceipt = worker.handleNext();
            if (maybeReceipt.isEmpty()) {
                continue;
            }
            AuthorityRuntimeReceipt receipt = maybeReceipt.orElseThrow();
            observe(receipt);
            if (receipt.aggregateId().equals(bootAggregateId)) {
                return receipt;
            }
        }
        throw new IllegalStateException("timed out waiting for auction escrow store-backed boot apply");
    }

    private void observe(AuthorityRuntimeReceipt receipt) {
        if (receipt.status() == AuthorityDecisionStatus.ACCEPTED && !receipt.replayed()) {
            acceptedApplyCount++;
        }
    }

    private void sendBootCommand(AuthorityCommand<AuctionEscrowCommand> command) {
        try {
            kafka.producer().send(new ProducerRecord<>(
                            config.commandTopic(),
                            command.envelope().aggregateId().value(),
                            AuctionEscrowCommandWireCodec.encode(command)))
                    .get(BOOT_COMMAND_SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending auction escrow boot command", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("timed out sending auction escrow boot command", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to send auction escrow boot command", exception);
        }
    }

    private static String clientId(AuctionEscrowBackendConfig config) {
        return config.securityContext().identity().instanceId().value();
    }

    private static List<InetSocketAddress> cassandraContactPoints(String contactPoints) {
        List<InetSocketAddress> endpoints = Arrays.stream(requireNonBlank(contactPoints, "contactPoints").split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> hostPort("cassandraContactPoints", value))
                .map(endpoint -> InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port()))
                .toList();
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("cassandraContactPoints must contain at least one host:port");
        }
        return endpoints;
    }

    private static ValkeyClientHandle valkey(String endpoint) {
        HostPort hostPort = hostPort("valkeyEndpoint", endpoint);
        return ValkeyClientHandle.create(hostPort.host(), hostPort.port());
    }

    private static HostPort hostPort(String label, String value) {
        String checked = requireNonBlank(value, label);
        int separator = checked.lastIndexOf(':');
        if (separator <= 0 || separator == checked.length() - 1) {
            throw new IllegalArgumentException(label + " must be host:port");
        }
        int port;
        try {
            port = Integer.parseInt(checked.substring(separator + 1).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " port must be numeric", exception);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(label + " port must be between 1 and 65535");
        }
        return new HostPort(checked.substring(0, separator).trim(), port);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static void closeAll(AutoCloseable... closeables) {
        RuntimeException failure = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("failed to close auction escrow backend client", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record HostPort(String host, int port) {
        private HostPort {
            host = requireNonBlank(host, "host");
        }
    }
}
