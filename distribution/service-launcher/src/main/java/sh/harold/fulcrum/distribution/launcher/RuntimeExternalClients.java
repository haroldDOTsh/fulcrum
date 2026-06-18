package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.agones.allocator.AgonesAllocatorRestClient;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.ObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.S3ObjectStorageAdapter;
import sh.harold.fulcrum.data.store.cassandra.CassandraClientHandle;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;
import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.velocity.VelocityRouteBridgeClient;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class RuntimeExternalClients implements AutoCloseable {
    private static final String PAPER_ARTIFACT_BUCKET = "artifact-store";

    private final Optional<AuthorityClients> authority;
    private final Optional<ControllerClients> controller;
    private final Optional<WorkerClients> worker;
    private final Optional<PaperClients> paper;
    private final Optional<VelocityClients> velocity;
    private final List<ServiceClients> ownedClients;

    private RuntimeExternalClients(
            Optional<AuthorityClients> authority,
            Optional<ControllerClients> controller,
            Optional<WorkerClients> worker,
            Optional<PaperClients> paper,
            Optional<VelocityClients> velocity) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.velocity = Objects.requireNonNull(velocity, "velocity");
        List<ServiceClients> clients = new ArrayList<>();
        authority.ifPresent(clients::add);
        controller.ifPresent(clients::add);
        worker.ifPresent(clients::add);
        paper.ifPresent(clients::add);
        velocity.ifPresent(clients::add);
        this.ownedClients = List.copyOf(clients);
    }

    static RuntimeExternalClients create(RuntimeConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new RuntimeExternalClients(
                settings.authority().map(RuntimeExternalClients::authorityClients),
                settings.controller().map(RuntimeExternalClients::controllerClients),
                settings.worker().map(RuntimeExternalClients::workerClients),
                settings.paper().map(RuntimeExternalClients::paperClients),
                settings.velocity().map(RuntimeExternalClients::velocityClients));
    }

    Optional<AuthorityClients> authority() {
        return authority;
    }

    Optional<ControllerClients> controller() {
        return controller;
    }

    Optional<WorkerClients> worker() {
        return worker;
    }

    Optional<PaperClients> paper() {
        return paper;
    }

    Optional<VelocityClients> velocity() {
        return velocity;
    }

    List<String> redactedSummary() {
        List<String> summary = new ArrayList<>();
        ownedClients.stream()
                .sorted((left, right) -> left.role().id().compareTo(right.role().id()))
                .forEach(clients -> summary.addAll(clients.redactedSummary()));
        return List.copyOf(summary);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int index = ownedClients.size() - 1; index >= 0; index--) {
            try {
                ownedClients.get(index).close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static AuthorityClients authorityClients(RuntimeConnectionSettings.AuthorityConnections settings) {
        Map<String, KafkaClientBundle> kafkaByAuthorityDomain = new LinkedHashMap<>();
        for (String authorityDomain : AuthorityWorkerCatalog.authorityDomains()) {
            kafkaByAuthorityDomain.put(
                    authorityDomain,
                    KafkaClientBundle.create(
                            bootstrapServers(settings.kafkaBootstrapServers()),
                            "fulcrum-authority-service-" + authorityDomain,
                            "fulcrum-authority-service-" + authorityDomain));
        }
        return new AuthorityClients(
                kafkaByAuthorityDomain,
                PostgresClientHandle.create(
                        settings.postgres().jdbcUrl(),
                        settings.postgres().username(),
                        settings.postgres().password()),
                CassandraClientHandle.createLazy(
                        settings.cassandraContactPoints().stream()
                                .map(RuntimeExternalClients::socketAddress)
                                .toList(),
                        settings.cassandraLocalDatacenter()),
                ValkeyClientHandle.create(
                        settings.valkeyEndpoint().host(),
                        settings.valkeyEndpoint().port()));
    }

    private static ControllerClients controllerClients(RuntimeConnectionSettings.ControllerConnections settings) {
        Map<String, KafkaClientBundle> kafkaByControllerDomain = new LinkedHashMap<>();
        for (String controllerDomain : ControllerWorkerCatalog.controllerDomains()) {
            kafkaByControllerDomain.put(
                    controllerDomain,
                    KafkaClientBundle.create(
                            bootstrapServers(settings.controlKafkaBootstrapServers()),
                            "fulcrum-controller-service-" + controllerDomain,
                            "fulcrum-controller-service"));
        }
        return new ControllerClients(
                kafkaByControllerDomain,
                KafkaClientBundle.create(
                        bootstrapServers(settings.controlKafkaBootstrapServers()),
                        "fulcrum-controller-service-host-observation-route",
                        "fulcrum-controller-service-host-observation-route"),
                agonesAllocator(settings),
                settings);
    }

    private static AgonesAllocatorRestClient agonesAllocator(RuntimeConnectionSettings.ControllerConnections settings) {
        if (settings.agonesAllocatorClientCertificatePath().isPresent()) {
            return AgonesAllocatorRestClient.mtls(
                    settings.agonesAllocatorUrl(),
                    settings.agonesNamespace(),
                    settings.agonesAllocatorClientCertificatePath().orElseThrow(),
                    settings.agonesAllocatorClientKeyPath().orElseThrow(),
                    settings.agonesAllocatorCaCertificatePath().orElseThrow(),
                    settings.agonesAllocatorDisableHostnameVerification());
        }
        if (settings.agonesAllocatorCaCertificatePath().isPresent()) {
            return AgonesAllocatorRestClient.tls(
                    settings.agonesAllocatorUrl(),
                    settings.agonesNamespace(),
                    settings.agonesAllocatorCaCertificatePath().orElseThrow(),
                    settings.agonesAllocatorDisableHostnameVerification());
        }
        return new AgonesAllocatorRestClient(settings.agonesAllocatorUrl(), settings.agonesNamespace());
    }

    private static WorkerClients workerClients(RuntimeConnectionSettings.WorkerConnections settings) {
        return new WorkerClients(
                KafkaClientBundle.create(
                        bootstrapServers(settings.workerKafkaBootstrapServers()),
                        ExternalWorkerJobWorker.CLIENT_ID,
                        ExternalWorkerJobWorker.GROUP_ID),
                objectStorage(settings.objectStore(), settings.objectBucket()),
                settings);
    }

    private static PaperClients paperClients(RuntimeConnectionSettings.PaperConnections settings) {
        return new PaperClients(
                KafkaClientBundle.create(
                        bootstrapServers(settings.paperKafkaBootstrapServers()),
                        "fulcrum-paper-agent-" + settings.sessionId().value(),
                        "fulcrum-paper-agent-" + settings.sessionId().value()),
                objectStorage(settings.objectStore(), PAPER_ARTIFACT_BUCKET),
                PAPER_ARTIFACT_BUCKET,
                ValkeyClientHandle.create(settings.valkeyEndpoint().host(), settings.valkeyEndpoint().port()),
                settings);
    }

    static ObjectStorageAdapter objectStorage(
            RuntimeConnectionSettings.ObjectStoreConnection settings,
            String bucket) {
        return switch (settings.mode()) {
            case LOCAL -> new LocalObjectStorageAdapter(settings.localRoot().orElseThrow(), bucket);
            case S3 -> {
                RuntimeConnectionSettings.S3ObjectStoreConnection s3 = settings.s3().orElseThrow();
                yield new S3ObjectStorageAdapter(
                        s3.endpoint(),
                        s3.region(),
                        s3.accessKey(),
                        s3.secretKey(),
                        bucket);
            }
        };
    }

    private static VelocityClients velocityClients(RuntimeConnectionSettings.VelocityConnections settings) {
        return new VelocityClients(
                KafkaClientBundle.create(
                        bootstrapServers(settings.velocityKafkaBootstrapServers()),
                        "fulcrum-velocity-agent",
                        "fulcrum-velocity-agent"),
                new VelocityRouteBridgeClient(settings.routeBridgeUrl()),
                ValkeyClientHandle.create(settings.valkeyEndpoint().host(), settings.valkeyEndpoint().port()),
                settings);
    }

    private static String bootstrapServers(List<RuntimeConnectionSettings.HostPort> endpoints) {
        return endpoints.stream()
                .map(RuntimeConnectionSettings.HostPort::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static InetSocketAddress socketAddress(RuntimeConnectionSettings.HostPort endpoint) {
        return InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port());
    }

    interface ServiceClients extends AutoCloseable {
        LaunchRole role();

        List<String> redactedSummary();

        @Override
        void close();
    }

    record AuthorityClients(
            Map<String, KafkaClientBundle> kafkaByAuthorityDomain,
            PostgresClientHandle postgres,
            CassandraClientHandle cassandra,
            ValkeyClientHandle valkey) implements ServiceClients {
        AuthorityClients {
            kafkaByAuthorityDomain = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(kafkaByAuthorityDomain, "kafkaByAuthorityDomain")));
            if (kafkaByAuthorityDomain.isEmpty()) {
                throw new IllegalArgumentException("kafkaByAuthorityDomain must not be empty");
            }
            postgres = Objects.requireNonNull(postgres, "postgres");
            cassandra = Objects.requireNonNull(cassandra, "cassandra");
            valkey = Objects.requireNonNull(valkey, "valkey");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.AUTHORITY_SERVICE;
        }

        KafkaClientBundle kafka(String authorityDomain) {
            KafkaClientBundle bundle = kafkaByAuthorityDomain.get(Objects.requireNonNull(authorityDomain, "authorityDomain"));
            if (bundle == null) {
                throw new IllegalArgumentException("No Kafka client bundle for authority domain " + authorityDomain);
            }
            return bundle;
        }

        @Override
        public List<String> redactedSummary() {
            KafkaClientBundle firstKafka = kafkaByAuthorityDomain.values().iterator().next();
            return List.of(
                    role().id() + ": kafkaClient=" + firstKafka.description()
                            + "|domains=" + kafkaByAuthorityDomain.keySet(),
                    role().id() + ": postgresClient=" + postgres.redactedDescription(),
                    role().id() + ": cassandraClient=" + cassandra.description(),
                    role().id() + ": valkeyClient=" + valkey.description());
        }

        @Override
        public void close() {
            closeAll(kafkaByAuthorityDomain.values());
            closeAll(valkey, cassandra, postgres);
        }
    }

    record ControllerClients(
            Map<String, KafkaClientBundle> kafkaByControllerDomain,
            KafkaClientBundle hostObservationKafka,
            HostAllocationPort allocationPort,
            RuntimeConnectionSettings.ControllerConnections settings) implements ServiceClients {
        ControllerClients {
            kafkaByControllerDomain = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(kafkaByControllerDomain, "kafkaByControllerDomain")));
            if (kafkaByControllerDomain.isEmpty()) {
                throw new IllegalArgumentException("kafkaByControllerDomain must not be empty");
            }
            hostObservationKafka = Objects.requireNonNull(hostObservationKafka, "hostObservationKafka");
            allocationPort = Objects.requireNonNull(allocationPort, "allocationPort");
            settings = Objects.requireNonNull(settings, "settings");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.CONTROLLER_SERVICE;
        }

        KafkaClientBundle controlKafka(String controllerDomain) {
            KafkaClientBundle bundle = kafkaByControllerDomain.get(Objects.requireNonNull(controllerDomain, "controllerDomain"));
            if (bundle == null) {
                throw new IllegalArgumentException("No Kafka client bundle for controller domain " + controllerDomain);
            }
            return bundle;
        }

        @Override
        public List<String> redactedSummary() {
            KafkaClientBundle firstKafka = kafkaByControllerDomain.values().iterator().next();
            return List.of(
                    role().id() + ": controlKafkaClient=" + firstKafka.description()
                            + "|domains=" + kafkaByControllerDomain.keySet(),
                    role().id() + ": agonesAllocatorUrl=" + settings.agonesAllocatorUrl(),
                    role().id() + ": agonesNamespace=" + settings.agonesNamespace(),
                    role().id() + ": agonesAllocatorMtls=" + settings.agonesAllocatorClientCertificatePath().isPresent(),
                    role().id() + ": agonesAllocatorHostnameVerification=" + !settings.agonesAllocatorDisableHostnameVerification(),
                    role().id() + ": hostCommandTopic=" + settings.hostCommandTopic(),
                    role().id() + ": hostObservationTopic=" + settings.hostObservationTopic(),
                    role().id() + ": proxyRouteCommandTopic=" + settings.proxyRouteCommandTopic());
        }

        @Override
        public void close() {
            closeAll(kafkaByControllerDomain.values());
            closeAll(hostObservationKafka);
        }
    }

    record WorkerClients(
            KafkaClientBundle workerKafka,
            ObjectStorageAdapter objectStorage,
            RuntimeConnectionSettings.WorkerConnections settings) implements ServiceClients {
        WorkerClients {
            workerKafka = Objects.requireNonNull(workerKafka, "workerKafka");
            objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
            settings = Objects.requireNonNull(settings, "settings");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.WORKER_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": workerKafkaClient=" + workerKafka.description(),
                    role().id() + ": objectBucket=" + settings.objectBucket(),
                    role().id() + ": objectStoreMode=" + settings.objectStore().mode().name().toLowerCase(java.util.Locale.ROOT),
                    role().id() + ": workerTopics=" + settings.jobTopic() + "," + settings.resultTopic());
        }

        @Override
        public void close() {
            workerKafka.close();
        }
    }

    record PaperClients(
            KafkaClientBundle paperKafka,
            ObjectStorageAdapter objectStorage,
            String objectBucket,
            ValkeyClientHandle valkey,
            RuntimeConnectionSettings.PaperConnections settings) implements ServiceClients {
        PaperClients {
            paperKafka = Objects.requireNonNull(paperKafka, "paperKafka");
            objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
            objectBucket = Objects.requireNonNull(objectBucket, "objectBucket");
            valkey = Objects.requireNonNull(valkey, "valkey");
            settings = Objects.requireNonNull(settings, "settings");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.PAPER_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": paperKafkaClient=" + paperKafka.description(),
                    role().id() + ": paperServerRoot=" + settings.paperServerRoot(),
                    role().id() + ": objectBucket=" + objectBucket,
                    role().id() + ": objectStoreMode=" + settings.objectStore().mode().name().toLowerCase(java.util.Locale.ROOT),
                    role().id() + ": observationBridgeUrl=" + settings.observationBridgeUrl(),
                    role().id() + ": capabilityBridgeUrl=" + settings.capabilityBridgeUrl(),
                    role().id() + ": rewardBridgeUrl=" + settings.rewardBridgeUrl(),
                    role().id() + ": valkeyClient=" + valkey.description(),
                    role().id() + ": hostTopics=" + settings.hostCommandTopic() + "," + settings.hostObservationTopic(),
                    role().id() + ": rewardCommands=" + settings.rewardEconomyCommandTopic()
                            + "," + settings.rewardStatsCommandTopic());
        }

        @Override
        public void close() {
            closeAll(paperKafka, valkey);
        }
    }

    record VelocityClients(
            KafkaClientBundle velocityKafka,
            VelocityRouteBridgeClient routeBridgeClient,
            ValkeyClientHandle valkey,
            RuntimeConnectionSettings.VelocityConnections settings) implements ServiceClients {
        VelocityClients {
            velocityKafka = Objects.requireNonNull(velocityKafka, "velocityKafka");
            routeBridgeClient = Objects.requireNonNull(routeBridgeClient, "routeBridgeClient");
            valkey = Objects.requireNonNull(valkey, "valkey");
            settings = Objects.requireNonNull(settings, "settings");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.VELOCITY_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": velocityKafkaClient=" + velocityKafka.description(),
                    role().id() + ": velocityServerRoot=" + settings.velocityServerRoot(),
                    role().id() + ": routeBridgeUrl=" + settings.routeBridgeUrl(),
                    role().id() + ": loginGateBridgeUrl=" + settings.loginGateBridgeUrl(),
                    role().id() + ": proxyRouteCommandTopic=" + settings.proxyRouteCommandTopic(),
                    role().id() + ": routeCommandTopic=" + settings.routeCommandTopic(),
                    role().id() + ": queueRosterCommandTopic=" + settings.queueRosterCommandTopic(),
                    role().id() + ": presenceCommandTopic=" + settings.presenceCommandTopic(),
                    role().id() + ": sharedShardPlacementCommandTopic=" + settings.sharedShardPlacementCommandTopic(),
                    role().id() + ": routeAttemptCommandTopic=" + settings.routeAttemptCommandTopic(),
                    role().id() + ": lifecycleTraceCommandTopic=" + settings.lifecycleTraceCommandTopic(),
                    role().id() + ": sharedShardAllocationStateTopic=" + settings.sharedShardAllocationStateTopic(),
                    role().id() + ": lobbyRouting=experienceId=" + settings.lobbyExperienceId().value()
                            + "|poolId=" + settings.lobbyPoolId().value()
                            + "|fleet=" + settings.lobbyAgonesFleetName()
                            + "|resolvedManifestId=" + settings.lobbyResolvedManifestId().value(),
                    role().id() + ": loginGateScope=" + settings.loginGateScope(),
                    role().id() + ": valkeyClient=" + valkey.description());
        }

        @Override
        public void close() {
            closeAll(velocityKafka, valkey);
        }
    }

    private static void closeAll(AutoCloseable... closeables) {
        closeAll(List.of(closeables));
    }

    private static void closeAll(Iterable<? extends AutoCloseable> closeables) {
        RuntimeException failure = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception exception) {
                RuntimeException wrapped = exception instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(exception);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
