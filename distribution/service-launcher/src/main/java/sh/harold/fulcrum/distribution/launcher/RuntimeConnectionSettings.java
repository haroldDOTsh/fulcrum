package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;
import sh.harold.fulcrum.host.paper.PaperAllocatedAssignmentFile;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class RuntimeConnectionSettings {
    private final Map<LaunchRole, ServiceConnections> services;

    private RuntimeConnectionSettings(Map<LaunchRole, ServiceConnections> services) {
        this.services = Map.copyOf(Objects.requireNonNull(services, "services"));
    }

    static RuntimeConnectionSettings resolve(LaunchPlan plan, RuntimeEnvironment environment) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(environment, "environment");
        if (!plan.canStart(environment)) {
            throw new RuntimeConfigurationException("Cannot resolve runtime connection settings with missing bindings");
        }
        Map<LaunchRole, ServiceConnections> resolved = new EnumMap<>(LaunchRole.class);
        for (LaunchEntry entry : plan.entries()) {
            resolved.put(entry.role(), resolve(entry.role(), environment));
        }
        return new RuntimeConnectionSettings(resolved);
    }

    ServiceConnections require(LaunchRole role) {
        ServiceConnections settings = services.get(Objects.requireNonNull(role, "role"));
        if (settings == null) {
            throw new RuntimeConfigurationException("No runtime connection settings were resolved for " + role.id());
        }
        return settings;
    }

    Optional<AuthorityConnections> authority() {
        return Optional.ofNullable((AuthorityConnections) services.get(LaunchRole.AUTHORITY_SERVICE));
    }

    Optional<ControllerConnections> controller() {
        return Optional.ofNullable((ControllerConnections) services.get(LaunchRole.CONTROLLER_SERVICE));
    }

    Optional<WorkerConnections> worker() {
        return Optional.ofNullable((WorkerConnections) services.get(LaunchRole.WORKER_AGENT));
    }

    Optional<PaperConnections> paper() {
        return Optional.ofNullable((PaperConnections) services.get(LaunchRole.PAPER_AGENT));
    }

    Optional<VelocityConnections> velocity() {
        return Optional.ofNullable((VelocityConnections) services.get(LaunchRole.VELOCITY_AGENT));
    }

    List<String> redactedSummary() {
        List<String> summary = new ArrayList<>();
        services.values().stream()
                .sorted((left, right) -> left.role().id().compareTo(right.role().id()))
                .forEach(settings -> summary.addAll(settings.redactedSummary()));
        return List.copyOf(summary);
    }

    private static ServiceConnections resolve(LaunchRole role, RuntimeEnvironment environment) {
        return switch (role) {
            case AUTHORITY_SERVICE -> new AuthorityConnections(
                    parseHostPorts("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS")),
                    new PostgresJdbcSettings(
                            requirePostgresJdbcUrl(required(environment, "FULCRUM_POSTGRES_JDBC_URL")),
                            required(environment, "FULCRUM_POSTGRES_USERNAME"),
                            required(environment, "FULCRUM_POSTGRES_PASSWORD")),
                    parseHostPorts("FULCRUM_CASSANDRA_CONTACT_POINTS", required(environment, "FULCRUM_CASSANDRA_CONTACT_POINTS")),
                    optional(environment, "FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1"),
                    parseHostPort("FULCRUM_VALKEY_ENDPOINT", required(environment, "FULCRUM_VALKEY_ENDPOINT")));
            case CONTROLLER_SERVICE -> new ControllerConnections(
                    parseHostPorts("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS", required(environment, "FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS")),
                    requireHttpUri("FULCRUM_AGONES_ALLOCATOR_URL", required(environment, "FULCRUM_AGONES_ALLOCATOR_URL")),
                    required(environment, "FULCRUM_AGONES_NAMESPACE"),
                    optionalPath(environment, "FULCRUM_AGONES_ALLOCATOR_CLIENT_CERT_PATH"),
                    optionalPath(environment, "FULCRUM_AGONES_ALLOCATOR_CLIENT_KEY_PATH"),
                    optionalPath(environment, "FULCRUM_AGONES_ALLOCATOR_CA_CERT_PATH"),
                    optionalBoolean(environment, "FULCRUM_AGONES_ALLOCATOR_DISABLE_HOSTNAME_VERIFICATION", false),
                    optionalBind(
                            environment,
                            "FULCRUM_AUTHORITY_REGISTRATION_BIND_HOST",
                            "FULCRUM_AUTHORITY_REGISTRATION_PORT",
                            18085),
                    required(environment, "FULCRUM_CONTROL_STATE_TOPIC"),
                    required(environment, "FULCRUM_HOST_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_HOST_OBSERVATION_TOPIC"),
                    required(environment, "FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC"));
            case WORKER_AGENT -> new WorkerConnections(
                    parseHostPorts(
                            "FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS",
                            required(environment, "FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS")),
                    required(environment, "FULCRUM_WORKER_JOB_TOPIC"),
                    required(environment, "FULCRUM_WORKER_RESULT_TOPIC"),
                    required(environment, "FULCRUM_WORKER_OBJECT_BUCKET"),
                    objectStore(environment));
            case PAPER_AGENT -> {
                ArtifactPin worldArtifact = new ArtifactPin(
                        new ArtifactId(required(environment, "FULCRUM_PAPER_WORLD_ARTIFACT_ID")),
                        required(environment, "FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST"),
                        required(environment, "FULCRUM_PAPER_WORLD_ARTIFACT_COMPATIBILITY"));
                ResolvedManifest resolvedManifest = new ResolvedManifest(
                        new ResolvedManifestId(required(environment, "FULCRUM_PAPER_RESOLVED_MANIFEST_ID")),
                        new ArtifactId(required(environment, "FULCRUM_PAPER_CODE_ARTIFACT_ID")),
                        List.of(worldArtifact),
                        List.of(),
                        required(environment, "FULCRUM_PAPER_HOST_RUNTIME_ABI"));
                Path paperServerRoot = Path.of(required(environment, "FULCRUM_PAPER_SERVER_ROOT"));
                yield new PaperConnections(
                        paperServerRoot,
                        environment.value("FULCRUM_PAPER_ALLOCATION_FILE")
                                .map(Path::of)
                                .orElseGet(() -> PaperAllocatedAssignmentFile.defaultPath(paperServerRoot)),
                        objectStore(environment),
                        parseHostPorts(
                                "FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS",
                                required(environment, "FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS")),
                        requireHttpUri("FULCRUM_PAPER_AGONES_SDK_URL", required(environment, "FULCRUM_PAPER_AGONES_SDK_URL")),
                        requireHttpUriWithExplicitPort(
                                "FULCRUM_PAPER_OBSERVATION_BRIDGE_URL",
                                required(environment, "FULCRUM_PAPER_OBSERVATION_BRIDGE_URL")),
                        requireHttpUriWithExplicitPort(
                                "FULCRUM_PAPER_CAPABILITY_BRIDGE_URL",
                                required(environment, "FULCRUM_PAPER_CAPABILITY_BRIDGE_URL")),
                        requireHttpUriWithExplicitPort(
                                "FULCRUM_PAPER_REWARD_BRIDGE_URL",
                                required(environment, "FULCRUM_PAPER_REWARD_BRIDGE_URL")),
                        parseHostPort("FULCRUM_VALKEY_ENDPOINT", required(environment, "FULCRUM_VALKEY_ENDPOINT")),
                        required(environment, "FULCRUM_HOST_COMMAND_TOPIC"),
                        required(environment, "FULCRUM_HOST_OBSERVATION_TOPIC"),
                        requirePositiveInt(
                                "FULCRUM_PAPER_REWARD_DELIVERY_COPIES",
                                optional(environment, "FULCRUM_PAPER_REWARD_DELIVERY_COPIES", "1")),
                        new ExperienceId(required(environment, "FULCRUM_PAPER_EXPERIENCE_ID")),
                        new SessionId(required(environment, "FULCRUM_PAPER_SESSION_ID")),
                        new SlotId(required(environment, "FULCRUM_PAPER_SLOT_ID")),
                        resolvedManifest,
                        worldArtifact,
                        required(environment, "FULCRUM_PAPER_SESSION_OWNER_TOKEN"),
                        requireDuration("FULCRUM_PAPER_SESSION_LEASE", required(environment, "FULCRUM_PAPER_SESSION_LEASE")));
            }
            case VELOCITY_AGENT -> new VelocityConnections(
                    Path.of(required(environment, "FULCRUM_VELOCITY_SERVER_ROOT")),
                    parseHostPorts(
                            "FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS",
                            required(environment, "FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS")),
                    requireHttpUriWithExplicitPort(
                            "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL",
                            required(environment, "FULCRUM_VELOCITY_ROUTE_BRIDGE_URL")),
                    requireHttpUriWithExplicitPort(
                            "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL",
                    required(environment, "FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL")),
                    required(environment, "FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_ROUTE_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_PRESENCE_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC"),
                    new ExperienceId(required(environment, "FULCRUM_LOBBY_EXPERIENCE_ID")),
                    new PoolId(required(environment, "FULCRUM_LOBBY_POOL_ID")),
                    required(environment, "FULCRUM_LOBBY_AGONES_FLEET_NAME"),
                    requirePositiveInt("FULCRUM_LOBBY_TARGET_CAPACITY", required(environment, "FULCRUM_LOBBY_TARGET_CAPACITY")),
                    requirePositiveInt("FULCRUM_LOBBY_HARD_CAPACITY", required(environment, "FULCRUM_LOBBY_HARD_CAPACITY")),
                    new ResolvedManifestId(required(environment, "FULCRUM_LOBBY_RESOLVED_MANIFEST_ID")),
                    required(environment, "FULCRUM_LOBBY_CAPABILITY_SCOPE_FINGERPRINT"),
                    required(environment, "FULCRUM_LOGIN_GATE_SCOPE"),
                    requireDuration(
                            "FULCRUM_VELOCITY_PRESENCE_LEASE",
                            required(environment, "FULCRUM_VELOCITY_PRESENCE_LEASE")),
                    parseHostPort("FULCRUM_VALKEY_ENDPOINT", required(environment, "FULCRUM_VALKEY_ENDPOINT")));
            case ALL -> throw new RuntimeConfigurationException("Cannot resolve connections for aggregate role " + role.id());
        };
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .map(value -> requireNonBlank(value, name))
                .orElseThrow(() -> new RuntimeConfigurationException("Missing required runtime binding " + name));
    }

    private static String optional(RuntimeEnvironment environment, String name, String defaultValue) {
        return environment.value(name)
                .map(value -> requireNonBlank(value, name))
                .orElseGet(() -> requireNonBlank(defaultValue, name));
    }

    private static boolean optionalBoolean(RuntimeEnvironment environment, String name, boolean defaultValue) {
        return environment.value(name)
                .map(value -> requireBoolean(name, value))
                .orElse(defaultValue);
    }

    private static Optional<HostPort> optionalBind(
            RuntimeEnvironment environment,
            String hostName,
            String portName,
            int defaultPort) {
        Optional<String> maybeHost = environment.value(hostName)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (maybeHost.isEmpty()) {
            return Optional.empty();
        }
        String host = requireNonBlank(maybeHost.orElseThrow(), hostName);
        int port = environment.value(portName)
                .map(value -> parsePort(portName, value))
                .orElse(defaultPort);
        return Optional.of(new HostPort(host, port));
    }

    static ObjectStoreConnection objectStore(RuntimeEnvironment environment) {
        String mode = optional(environment, "FULCRUM_OBJECT_STORE_MODE", "local")
                .toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "local" -> ObjectStoreConnection.local(Path.of(required(environment, "FULCRUM_OBJECT_STORE_ROOT")));
            case "s3" -> ObjectStoreConnection.s3(new S3ObjectStoreConnection(
                    requireHttpUri("FULCRUM_OBJECT_STORE_ENDPOINT", required(environment, "FULCRUM_OBJECT_STORE_ENDPOINT")),
                    optional(environment, "FULCRUM_OBJECT_STORE_REGION", "us-east-1"),
                    required(environment, "FULCRUM_OBJECT_STORE_ACCESS_KEY"),
                    required(environment, "FULCRUM_OBJECT_STORE_SECRET_KEY")));
            default -> throw new RuntimeConfigurationException(
                    "FULCRUM_OBJECT_STORE_MODE must be local or s3, got " + mode);
        };
    }

    private static Optional<Path> optionalPath(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of);
    }

    private static String requirePostgresJdbcUrl(String value) {
        String checked = requireNonBlank(value, "FULCRUM_POSTGRES_JDBC_URL");
        if (!checked.startsWith("jdbc:postgresql://")) {
            throw new RuntimeConfigurationException("FULCRUM_POSTGRES_JDBC_URL must be a PostgreSQL JDBC URL");
        }
        return checked;
    }

    private static URI requireHttpUri(String name, String value) {
        URI uri;
        try {
            uri = new URI(requireNonBlank(value, name));
        } catch (URISyntaxException exception) {
            throw new RuntimeConfigurationException(name + " must be a valid URI", exception);
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new RuntimeConfigurationException(name + " must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RuntimeConfigurationException(name + " must include a host");
        }
        return uri;
    }

    private static URI requireHttpUriWithExplicitPort(String name, String value) {
        URI uri = requireHttpUri(name, value);
        if (uri.getPort() < 1 || uri.getPort() > 65_535) {
            throw new RuntimeConfigurationException(name + " must include an explicit port between 1 and 65535");
        }
        if (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath())) {
            throw new RuntimeConfigurationException(name + " must include a non-root path");
        }
        return uri;
    }

    private static Duration requireDuration(String name, String value) {
        Duration duration;
        try {
            duration = Duration.parse(requireNonBlank(value, name));
        } catch (RuntimeException exception) {
            throw new RuntimeConfigurationException(name + " must be an ISO-8601 duration", exception);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new RuntimeConfigurationException(name + " must be positive");
        }
        return duration;
    }

    private static int requirePositiveInt(String name, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(requireNonBlank(value, name));
        } catch (NumberFormatException exception) {
            throw new RuntimeConfigurationException(name + " must be an integer", exception);
        }
        if (parsed <= 0) {
            throw new RuntimeConfigurationException(name + " must be positive");
        }
        return parsed;
    }

    private static long requirePositiveLong(String name, String value) {
        long parsed;
        try {
            parsed = Long.parseLong(requireNonBlank(value, name));
        } catch (NumberFormatException exception) {
            throw new RuntimeConfigurationException(name + " must be an integer", exception);
        }
        if (parsed <= 0) {
            throw new RuntimeConfigurationException(name + " must be positive");
        }
        return parsed;
    }

    private static List<HostPort> parseHostPorts(String name, String value) {
        List<HostPort> endpoints = new ArrayList<>();
        for (String part : requireNonBlank(value, name).split(",")) {
            endpoints.add(parseHostPort(name, part));
        }
        return List.copyOf(endpoints);
    }

    private static HostPort parseHostPort(String name, String value) {
        String checked = requireNonBlank(value, name);
        int separator = checked.lastIndexOf(':');
        if (separator <= 0 || separator == checked.length() - 1) {
            throw new RuntimeConfigurationException(name + " must be host:port");
        }
        String host = requireNonBlank(checked.substring(0, separator), name + " host");
        int port;
        try {
            port = Integer.parseInt(checked.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new RuntimeConfigurationException(name + " port must be a number", exception);
        }
        if (port < 1 || port > 65_535) {
            throw new RuntimeConfigurationException(name + " port must be between 1 and 65535");
        }
        return new HostPort(host, port);
    }

    private static int parsePort(String name, String value) {
        int port;
        try {
            port = Integer.parseInt(requireNonBlank(value, name));
        } catch (NumberFormatException exception) {
            throw new RuntimeConfigurationException(name + " port must be a number", exception);
        }
        if (port < 1 || port > 65_535) {
            throw new RuntimeConfigurationException(name + " port must be between 1 and 65535");
        }
        return port;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new RuntimeConfigurationException(label + " must not be blank");
        }
        return checked;
    }

    private static boolean requireBoolean(String name, String value) {
        return switch (requireNonBlank(value, name).toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new RuntimeConfigurationException(name + " must be true or false");
        };
    }

    interface ServiceConnections {
        LaunchRole role();

        List<String> redactedSummary();
    }

    record AuthorityConnections(
            List<HostPort> kafkaBootstrapServers,
            PostgresJdbcSettings postgres,
            List<HostPort> cassandraContactPoints,
            String cassandraLocalDatacenter,
            HostPort valkeyEndpoint) implements ServiceConnections {
        AuthorityConnections {
            kafkaBootstrapServers = List.copyOf(kafkaBootstrapServers);
            postgres = Objects.requireNonNull(postgres, "postgres");
            cassandraContactPoints = List.copyOf(cassandraContactPoints);
            cassandraLocalDatacenter = requireNonBlank(cassandraLocalDatacenter, "cassandraLocalDatacenter");
            valkeyEndpoint = Objects.requireNonNull(valkeyEndpoint, "valkeyEndpoint");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.AUTHORITY_SERVICE;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": kafka=" + kafkaBootstrapServers,
                    role().id() + ": postgres=" + postgres.redactedValue(),
                    role().id() + ": cassandra=" + cassandraContactPoints + "|localDatacenter=" + cassandraLocalDatacenter,
                    role().id() + ": valkey=" + valkeyEndpoint);
        }
    }

    record ControllerConnections(
            List<HostPort> controlKafkaBootstrapServers,
            URI agonesAllocatorUrl,
            String agonesNamespace,
            Optional<Path> agonesAllocatorClientCertificatePath,
            Optional<Path> agonesAllocatorClientKeyPath,
            Optional<Path> agonesAllocatorCaCertificatePath,
            boolean agonesAllocatorDisableHostnameVerification,
            Optional<HostPort> authorityRegistrationBind,
            String controlStateTopic,
            String hostCommandTopic,
            String hostObservationTopic,
            String proxyRouteCommandTopic) implements ServiceConnections {
        ControllerConnections {
            controlKafkaBootstrapServers = List.copyOf(controlKafkaBootstrapServers);
            agonesAllocatorUrl = Objects.requireNonNull(agonesAllocatorUrl, "agonesAllocatorUrl");
            agonesNamespace = requireNonBlank(agonesNamespace, "agonesNamespace");
            agonesAllocatorClientCertificatePath = Objects.requireNonNull(
                    agonesAllocatorClientCertificatePath,
                    "agonesAllocatorClientCertificatePath");
            agonesAllocatorClientKeyPath = Objects.requireNonNull(
                    agonesAllocatorClientKeyPath,
                    "agonesAllocatorClientKeyPath");
            agonesAllocatorCaCertificatePath = Objects.requireNonNull(
                    agonesAllocatorCaCertificatePath,
                    "agonesAllocatorCaCertificatePath");
            authorityRegistrationBind = Objects.requireNonNull(authorityRegistrationBind, "authorityRegistrationBind");
            boolean clientCertificateConfigured = agonesAllocatorClientCertificatePath.isPresent();
            boolean clientKeyConfigured = agonesAllocatorClientKeyPath.isPresent();
            boolean caCertificateConfigured = agonesAllocatorCaCertificatePath.isPresent();
            if (clientCertificateConfigured != clientKeyConfigured) {
                throw new RuntimeConfigurationException(
                        "Agones allocator mTLS requires client certificate and client key paths together");
            }
            if ((clientCertificateConfigured || clientKeyConfigured) && !caCertificateConfigured) {
                throw new RuntimeConfigurationException(
                        "Agones allocator mTLS requires a CA certificate path with the client certificate and key paths");
            }
            controlStateTopic = requireNonBlank(controlStateTopic, "controlStateTopic");
            hostCommandTopic = requireNonBlank(hostCommandTopic, "hostCommandTopic");
            hostObservationTopic = requireNonBlank(hostObservationTopic, "hostObservationTopic");
            proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.CONTROLLER_SERVICE;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": controlKafka=" + controlKafkaBootstrapServers,
                    role().id() + ": agonesAllocatorUrl=" + agonesAllocatorUrl,
                    role().id() + ": agonesNamespace=" + agonesNamespace,
                    role().id() + ": agonesAllocatorMtls=" + agonesAllocatorClientCertificatePath.isPresent(),
                    role().id() + ": agonesAllocatorHostnameVerification=" + !agonesAllocatorDisableHostnameVerification,
                    role().id() + ": authorityRegistrationBind=" + authorityRegistrationBind.map(HostPort::toString).orElse("disabled"),
                    role().id() + ": controlStateTopic=" + controlStateTopic,
                    role().id() + ": hostCommandTopic=" + hostCommandTopic,
                    role().id() + ": hostObservationTopic=" + hostObservationTopic,
                    role().id() + ": proxyRouteCommandTopic=" + proxyRouteCommandTopic);
        }
    }

    record WorkerConnections(
            List<HostPort> workerKafkaBootstrapServers,
            String jobTopic,
            String resultTopic,
            String objectBucket,
            ObjectStoreConnection objectStore) implements ServiceConnections {
        WorkerConnections {
            workerKafkaBootstrapServers = List.copyOf(workerKafkaBootstrapServers);
            jobTopic = requireNonBlank(jobTopic, "jobTopic");
            resultTopic = requireNonBlank(resultTopic, "resultTopic");
            objectBucket = requireNonBlank(objectBucket, "objectBucket");
            objectStore = Objects.requireNonNull(objectStore, "objectStore");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.WORKER_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            List<String> summary = new ArrayList<>(List.of(
                    role().id() + ": workerKafka=" + workerKafkaBootstrapServers,
                    role().id() + ": workerTopics=" + jobTopic + "," + resultTopic,
                    role().id() + ": objectBucket=" + objectBucket));
            summary.addAll(objectStore.redactedSummary(role()));
            return List.copyOf(summary);
        }

        Path objectStoreRoot() {
            return objectStore.localRoot().orElseThrow(() -> new RuntimeConfigurationException(
                    "worker-agent objectStoreRoot is only available for local object storage"));
        }
    }

    record PaperConnections(
            Path paperServerRoot,
            Path allocatedAssignmentFile,
            ObjectStoreConnection objectStore,
            List<HostPort> paperKafkaBootstrapServers,
            URI agonesSdkUrl,
            URI observationBridgeUrl,
            URI capabilityBridgeUrl,
            URI rewardBridgeUrl,
            HostPort valkeyEndpoint,
            String hostCommandTopic,
            String hostObservationTopic,
            int rewardDeliveryCopies,
            ExperienceId experienceId,
            SessionId sessionId,
            SlotId slotId,
            ResolvedManifest resolvedManifest,
            ArtifactPin worldArtifact,
            String sessionOwnerToken,
            Duration sessionLease) implements ServiceConnections {
        PaperConnections {
            paperServerRoot = Objects.requireNonNull(paperServerRoot, "paperServerRoot");
            allocatedAssignmentFile = Objects.requireNonNull(allocatedAssignmentFile, "allocatedAssignmentFile");
            objectStore = Objects.requireNonNull(objectStore, "objectStore");
            paperKafkaBootstrapServers = List.copyOf(paperKafkaBootstrapServers);
            agonesSdkUrl = Objects.requireNonNull(agonesSdkUrl, "agonesSdkUrl");
            observationBridgeUrl = Objects.requireNonNull(observationBridgeUrl, "observationBridgeUrl");
            capabilityBridgeUrl = Objects.requireNonNull(capabilityBridgeUrl, "capabilityBridgeUrl");
            rewardBridgeUrl = Objects.requireNonNull(rewardBridgeUrl, "rewardBridgeUrl");
            valkeyEndpoint = Objects.requireNonNull(valkeyEndpoint, "valkeyEndpoint");
            hostCommandTopic = requireNonBlank(hostCommandTopic, "hostCommandTopic");
            hostObservationTopic = requireNonBlank(hostObservationTopic, "hostObservationTopic");
            if (rewardDeliveryCopies <= 0) {
                throw new IllegalArgumentException("rewardDeliveryCopies must be positive");
            }
            experienceId = Objects.requireNonNull(experienceId, "experienceId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            resolvedManifest = Objects.requireNonNull(resolvedManifest, "resolvedManifest");
            worldArtifact = Objects.requireNonNull(worldArtifact, "worldArtifact");
            sessionOwnerToken = requireNonBlank(sessionOwnerToken, "sessionOwnerToken");
            sessionLease = Objects.requireNonNull(sessionLease, "sessionLease");
            if (sessionLease.isNegative() || sessionLease.isZero()) {
                throw new IllegalArgumentException("sessionLease must be positive");
            }
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.PAPER_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            List<String> summary = new ArrayList<>(List.of(
                    role().id() + ": paperServerRoot=" + paperServerRoot,
                    role().id() + ": allocatedAssignmentFile=" + allocatedAssignmentFile,
                    role().id() + ": paperKafka=" + paperKafkaBootstrapServers,
                    role().id() + ": agonesSdkUrl=" + agonesSdkUrl,
                    role().id() + ": observationBridgeUrl=" + observationBridgeUrl,
                    role().id() + ": capabilityBridgeUrl=" + capabilityBridgeUrl,
                    role().id() + ": rewardBridgeUrl=" + rewardBridgeUrl,
                    role().id() + ": valkey=" + valkeyEndpoint,
                    role().id() + ": hostTopics=" + hostCommandTopic + "," + hostObservationTopic,
                    role().id() + ": rewardDeliveryCopies=" + rewardDeliveryCopies,
                    role().id() + ": assignment=sessionId=" + sessionId.value()
                            + "|experienceId=" + experienceId.value()
                            + "|slotId=" + slotId.value()
                            + "|resolvedManifestId=" + resolvedManifest.resolvedManifestId().value(),
                    role().id() + ": worldArtifactId=" + worldArtifact.artifactId().value(),
                    role().id() + ": sessionOwnerToken=<redacted>"));
            summary.addAll(objectStore.redactedSummary(role()));
            return List.copyOf(summary);
        }

        Path objectStoreRoot() {
            return objectStore.localRoot().orElseThrow(() -> new RuntimeConfigurationException(
                    "paper-agent objectStoreRoot is only available for local object storage"));
        }
    }

    enum ObjectStoreMode {
        LOCAL,
        S3
    }

    record ObjectStoreConnection(
            ObjectStoreMode mode,
            Optional<Path> localRoot,
            Optional<S3ObjectStoreConnection> s3) {
        ObjectStoreConnection {
            mode = Objects.requireNonNull(mode, "mode");
            localRoot = Objects.requireNonNull(localRoot, "localRoot");
            s3 = Objects.requireNonNull(s3, "s3");
            if (mode == ObjectStoreMode.LOCAL && localRoot.isEmpty()) {
                throw new IllegalArgumentException("local object storage requires localRoot");
            }
            if (mode == ObjectStoreMode.S3 && s3.isEmpty()) {
                throw new IllegalArgumentException("S3 object storage requires s3 settings");
            }
        }

        static ObjectStoreConnection local(Path root) {
            Path checked = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            return new ObjectStoreConnection(
                    ObjectStoreMode.LOCAL,
                    Optional.of(checked),
                    Optional.empty());
        }

        static ObjectStoreConnection s3(S3ObjectStoreConnection settings) {
            return new ObjectStoreConnection(
                    ObjectStoreMode.S3,
                    Optional.empty(),
                    Optional.of(Objects.requireNonNull(settings, "settings")));
        }

        List<String> redactedSummary(LaunchRole role) {
            String prefix = role.id();
            if (mode == ObjectStoreMode.LOCAL) {
                return List.of(
                        prefix + ": objectStoreMode=local",
                        prefix + ": objectStoreRoot=" + localRoot.orElseThrow());
            }
            S3ObjectStoreConnection settings = s3.orElseThrow();
            return List.of(
                    prefix + ": objectStoreMode=s3",
                    prefix + ": objectStoreEndpoint=" + settings.endpoint(),
                    prefix + ": objectStoreRegion=" + settings.region(),
                    prefix + ": objectStoreAccessKey=<redacted>",
                    prefix + ": objectStoreSecretKey=<redacted>");
        }
    }

    record S3ObjectStoreConnection(
            URI endpoint,
            String region,
            String accessKey,
            String secretKey) {
        S3ObjectStoreConnection {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            region = requireNonBlank(region, "region");
            accessKey = requireNonBlank(accessKey, "accessKey");
            secretKey = requireNonBlank(secretKey, "secretKey");
        }
    }

    record VelocityConnections(
            Path velocityServerRoot,
            List<HostPort> velocityKafkaBootstrapServers,
            URI routeBridgeUrl,
            URI loginGateBridgeUrl,
            String proxyRouteCommandTopic,
            String routeCommandTopic,
            String queueRosterCommandTopic,
            String presenceCommandTopic,
            String sharedShardPlacementCommandTopic,
            String routeAttemptCommandTopic,
            String lifecycleTraceCommandTopic,
            String sharedShardAllocationStateTopic,
            ExperienceId lobbyExperienceId,
            PoolId lobbyPoolId,
            String lobbyAgonesFleetName,
            int lobbyTargetCapacity,
            int lobbyHardCapacity,
            ResolvedManifestId lobbyResolvedManifestId,
            String lobbyCapabilityScopeFingerprint,
            String loginGateScope,
            Duration presenceLease,
            HostPort valkeyEndpoint) implements ServiceConnections {
        VelocityConnections {
            velocityServerRoot = Objects.requireNonNull(velocityServerRoot, "velocityServerRoot");
            velocityKafkaBootstrapServers = List.copyOf(velocityKafkaBootstrapServers);
            routeBridgeUrl = Objects.requireNonNull(routeBridgeUrl, "routeBridgeUrl");
            loginGateBridgeUrl = Objects.requireNonNull(loginGateBridgeUrl, "loginGateBridgeUrl");
            proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
            routeCommandTopic = requireNonBlank(routeCommandTopic, "routeCommandTopic");
            queueRosterCommandTopic = requireNonBlank(queueRosterCommandTopic, "queueRosterCommandTopic");
            presenceCommandTopic = requireNonBlank(presenceCommandTopic, "presenceCommandTopic");
            sharedShardPlacementCommandTopic = requireNonBlank(sharedShardPlacementCommandTopic, "sharedShardPlacementCommandTopic");
            routeAttemptCommandTopic = requireNonBlank(routeAttemptCommandTopic, "routeAttemptCommandTopic");
            lifecycleTraceCommandTopic = requireNonBlank(lifecycleTraceCommandTopic, "lifecycleTraceCommandTopic");
            sharedShardAllocationStateTopic = requireNonBlank(sharedShardAllocationStateTopic, "sharedShardAllocationStateTopic");
            lobbyExperienceId = Objects.requireNonNull(lobbyExperienceId, "lobbyExperienceId");
            lobbyPoolId = Objects.requireNonNull(lobbyPoolId, "lobbyPoolId");
            lobbyAgonesFleetName = requireNonBlank(lobbyAgonesFleetName, "lobbyAgonesFleetName");
            if (lobbyTargetCapacity <= 0) {
                throw new RuntimeConfigurationException("lobbyTargetCapacity must be positive");
            }
            if (lobbyHardCapacity <= 0) {
                throw new RuntimeConfigurationException("lobbyHardCapacity must be positive");
            }
            lobbyResolvedManifestId = Objects.requireNonNull(lobbyResolvedManifestId, "lobbyResolvedManifestId");
            lobbyCapabilityScopeFingerprint = requireNonBlank(lobbyCapabilityScopeFingerprint, "lobbyCapabilityScopeFingerprint");
            loginGateScope = requireNonBlank(loginGateScope, "loginGateScope");
            presenceLease = Objects.requireNonNull(presenceLease, "presenceLease");
            if (presenceLease.isNegative() || presenceLease.isZero()) {
                throw new RuntimeConfigurationException("presenceLease must be positive");
            }
            valkeyEndpoint = Objects.requireNonNull(valkeyEndpoint, "valkeyEndpoint");
        }

        @Override
        public LaunchRole role() {
            return LaunchRole.VELOCITY_AGENT;
        }

        @Override
        public List<String> redactedSummary() {
            return List.of(
                    role().id() + ": velocityServerRoot=" + velocityServerRoot,
                    role().id() + ": velocityKafka=" + velocityKafkaBootstrapServers,
                    role().id() + ": routeBridgeUrl=" + routeBridgeUrl,
                    role().id() + ": loginGateBridgeUrl=" + loginGateBridgeUrl,
                    role().id() + ": proxyRouteCommandTopic=" + proxyRouteCommandTopic,
                    role().id() + ": routeCommandTopic=" + routeCommandTopic,
                    role().id() + ": queueRosterCommandTopic=" + queueRosterCommandTopic,
                    role().id() + ": presenceCommandTopic=" + presenceCommandTopic,
                    role().id() + ": sharedShardPlacementCommandTopic=" + sharedShardPlacementCommandTopic,
                    role().id() + ": routeAttemptCommandTopic=" + routeAttemptCommandTopic,
                    role().id() + ": lifecycleTraceCommandTopic=" + lifecycleTraceCommandTopic,
                    role().id() + ": sharedShardAllocationStateTopic=" + sharedShardAllocationStateTopic,
                    role().id() + ": lobbyRouting=experienceId=" + lobbyExperienceId.value()
                            + "|poolId=" + lobbyPoolId.value()
                            + "|fleet=" + lobbyAgonesFleetName
                            + "|resolvedManifestId=" + lobbyResolvedManifestId.value(),
                    role().id() + ": loginGateScope=" + loginGateScope,
                    role().id() + ": presenceLease=" + presenceLease,
                    role().id() + ": valkey=" + valkeyEndpoint);
        }
    }

    record PostgresJdbcSettings(String jdbcUrl, String username, String password) {
        PostgresJdbcSettings {
            jdbcUrl = requirePostgresJdbcUrl(jdbcUrl);
            username = requireNonBlank(username, "postgres username");
            password = requireNonBlank(password, "postgres password");
        }

        String redactedValue() {
            return "jdbcUrl=" + jdbcUrl + "|username=" + username + "|password=<redacted>";
        }
    }

    record HostPort(String host, int port) {
        HostPort {
            host = requireNonBlank(host, "host");
            if (port < 1 || port > 65_535) {
                throw new RuntimeConfigurationException("port must be between 1 and 65535");
            }
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}

final class RuntimeConfigurationException extends RuntimeException {
    RuntimeConfigurationException(String message) {
        super(message);
    }

    RuntimeConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
