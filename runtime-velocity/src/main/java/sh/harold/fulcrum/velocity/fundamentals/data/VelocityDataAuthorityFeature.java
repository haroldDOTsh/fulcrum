package sh.harold.fulcrum.velocity.fundamentals.data;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.velocitypowered.api.proxy.ProxyServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.guard.GameNodeCapabilityManifest;
import sh.harold.fulcrum.api.data.guard.GameNodeStartupAttestation;
import sh.harold.fulcrum.api.data.guard.GameNodeStorageGuard;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogDataAuthorityClient;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.data.impl.authority.InMemoryAuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.authority.KafkaAuthorityLog;
import sh.harold.fulcrum.api.data.impl.authority.WatermarkedDataAuthorityCache;
import sh.harold.fulcrum.api.data.impl.authority.events.CassandraAuthorityHotStateProjection;
import sh.harold.fulcrum.api.data.valkey.ValkeyAuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusAuthorityChannels;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

public class VelocityDataAuthorityFeature implements VelocityFeature {
    static final String SNAPSHOT_CACHE_MODE = "watermarked-snapshot-cache";
    static final String DEFAULT_COMMAND_TRANSPORT = "kafka";

    private static final String LOCAL_AUTHORITY_DISABLED_MESSAGE =
        "authority.mode=local is no longer available on Velocity game nodes. Game nodes must use "
            + "authority.mode=remote so registry-service remains the durable Data Authority owner.";
    private static final String UNSUPPORTED_COMMAND_TRANSPORT_MESSAGE =
        "Game-node Data Authority command ingress must use the durable Kafka command log. "
            + "Set authority.command-transport=kafka.";

    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.CommandSubmissionPort commandSubmissionPort;
    private DataAuthority.PlayerProfileReader profileReader;
    private DataAuthority.PlayerRankReader rankReader;
    private RuntimeDataAuthorityAttestation dataAuthorityAttestation;
    private RuntimeAuthorityDeliveryManifest authorityDeliveryManifest;
    private AutoCloseable authorityLog;
    private AutoCloseable hotReadResource;
    private ServiceLocator serviceLocator;
    private MessageBus messageBus;
    private MessageHandler snapshotInvalidationHandler;

    @Override
    public String getName() {
        return "DataAuthority";
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;

        serviceLocator.getRequiredService(ProxyServer.class);
        Path dataDirectory = serviceLocator.getRequiredService(Path.class);

        try {
            logger.info("Initializing Data Authority feature for Velocity...");

            Map<String, Object> config = loadDatabaseConfig(dataDirectory);
            GameNodeStartupAttestation.Report attestation = requireStartupAttestation(config);
            logger.info("Data Authority startup attestation passed: {}", attestation.summary());
            Map<String, Object> authorityConfig = section(config, "authority");
            String mode = stringValue(authorityConfig.get("mode"), "remote");
            String authorityServerId = stringValue(authorityConfig.get("server-id"), "registry-service");
            this.dataAuthorityAttestation = runtimeDataAuthorityAttestation(attestation, mode, SNAPSHOT_CACHE_MODE);
            this.authorityDeliveryManifest = runtimeAuthorityDeliveryManifest(
                attestation,
                mode,
                SNAPSHOT_CACHE_MODE,
                authorityServerId
            );
            if ("local".equalsIgnoreCase(mode)) {
                rejectLocalAuthorityMode();
            } else {
                initializeRemoteAuthority(config, authorityConfig);
            }

            registerAuthorityServices();
            logger.info("Data Authority initialized successfully for Velocity");
        } catch (Exception e) {
            logger.error("Failed to initialize Data Authority", e);
            throw new RuntimeException("Failed to initialize Data Authority", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data Authority feature...");

        unsubscribeSnapshotInvalidations();

        if (serviceLocator != null) {
            serviceLocator.unregister(DataAuthority.CommandPort.class);
            serviceLocator.unregister(DataAuthority.CommandSubmissionPort.class);
            serviceLocator.unregister(DataAuthority.PlayerProfileReader.class);
            serviceLocator.unregister(DataAuthority.PlayerRankReader.class);
            serviceLocator.unregister(RuntimeDataAuthorityAttestation.class);
            serviceLocator.unregister(RuntimeAuthorityDeliveryManifest.class);
        }

        closeAuthorityLog();
        closeHotReadResource();
        commandPort = null;
        commandSubmissionPort = null;
        profileReader = null;
        rankReader = null;
        dataAuthorityAttestation = null;
        authorityDeliveryManifest = null;
        logger.info("Data Authority shut down successfully");
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "MessageBus" };
    }

    private void initializeRemoteAuthority(Map<String, Object> rootConfig, Map<String, Object> authorityConfig) {
        MessageBus messageBus = serviceLocator.getRequiredService(MessageBus.class);
        String authorityServerId = stringValue(authorityConfig.get("server-id"), "registry-service");
        long timeoutMs = longValue(authorityConfig.get("request-timeout-ms"), 5000L);
        DataAuthority.CommandPort commandClient = commandClient(
            authorityConfig,
            Duration.ofMillis(timeoutMs)
        );
        long snapshotCacheMaxAgeMs = longValue(
            authorityConfig.get("snapshot-cache-max-age-ms"),
            WatermarkedDataAuthorityCache.DEFAULT_MAX_AGE.toMillis()
        );
        HotReadResource hotRead = hotReadResource(rootConfig, authorityConfig, snapshotCacheMaxAgeMs);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            commandClient,
            hotRead.profileReader(),
            hotRead.rankReader(),
            Duration.ofMillis(snapshotCacheMaxAgeMs),
            java.time.Clock.systemUTC(),
            hotRead.cacheStore()
        );
        hotReadResource = hotRead;
        subscribeSnapshotInvalidations(messageBus, authorityServerId, cache);
        commandPort = cache;
        commandSubmissionPort = commandClient instanceof DataAuthority.CommandSubmissionPort ? cache : null;
        profileReader = cache;
        rankReader = cache;
        logger.info(
            "Using remote Data Authority via {} command transport and cache/Cassandra hot reads through authority server {} "
                + "with watermarked snapshot cache max age {}ms",
            stringValue(authorityConfig.get("command-transport"), DEFAULT_COMMAND_TRANSPORT),
            authorityServerId,
            snapshotCacheMaxAgeMs
        );
    }

    private DataAuthority.CommandPort commandClient(
        Map<String, Object> authorityConfig,
        Duration timeout
    ) {
        String transport = stringValue(authorityConfig.get("command-transport"), DEFAULT_COMMAND_TRANSPORT);
        if (!"kafka".equalsIgnoreCase(transport) && !"log".equalsIgnoreCase(transport)) {
            throw new IllegalStateException(
                UNSUPPORTED_COMMAND_TRANSPORT_MESSAGE + " Unsupported value: " + transport
            );
        }

        closeAuthorityLog();
        Map<String, Object> kafkaConfig = section(authorityConfig, "kafka");
        KafkaAuthorityLog kafkaLog = new KafkaAuthorityLog(kafkaProperties(kafkaConfig));
        if (booleanValue(kafkaConfig.get("create-missing-topics"), false)) {
            kafkaLog.createMissingTopics();
        }
        if (booleanValue(kafkaConfig.get("validate-topology"), true)) {
            kafkaLog.validateTopology();
        }
        authorityLog = kafkaLog;
        return new AuthorityLogDataAuthorityClient(kafkaLog, timeout);
    }

    private static Properties kafkaProperties(Map<String, Object> kafkaConfig) {
        Properties properties = new Properties();
        properties.put(
            "bootstrap.servers",
            stringValue(kafkaConfig.get("bootstrap-servers"), "localhost:9092")
        );
        for (Map.Entry<String, Object> entry : kafkaConfig.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map<?, ?>) {
                continue;
            }
            if ("create-missing-topics".equals(entry.getKey()) || "validate-topology".equals(entry.getKey())) {
                continue;
            }
            properties.put(kafkaPropertyName(entry.getKey()), value.toString());
        }
        return properties;
    }

    private static String kafkaPropertyName(String key) {
        return switch (key) {
            case "bootstrap-servers" -> "bootstrap.servers";
            case "client-id" -> "client.id";
            case "security-protocol" -> "security.protocol";
            case "sasl-mechanism" -> "sasl.mechanism";
            case "sasl-jaas-config" -> "sasl.jaas.config";
            default -> key.replace('-', '.');
        };
    }

    private HotReadResource hotReadResource(
        Map<String, Object> rootConfig,
        Map<String, Object> authorityConfig,
        long snapshotCacheMaxAgeMs
    ) {
        closeHotReadResource();
        RedisClient redisClient = null;
        StatefulRedisConnection<String, String> redisConnection = null;
        CqlSession cassandraSession = null;
        try {
            AuthoritySnapshotCacheStore snapshotCacheStore = new InMemoryAuthoritySnapshotCacheStore();
            if (valkeySnapshotCacheEnabled(authorityConfig)) {
                try {
                    redisClient = RedisClient.create(redisUri(section(rootConfig, "redis")));
                    redisConnection = redisClient.connect();
                    redisConnection.sync().ping();
                    snapshotCacheStore = new ValkeyAuthoritySnapshotCacheStore(
                        redisConnection.sync(),
                        Duration.ofMillis(snapshotCacheMaxAgeMs)
                    );
                } catch (RuntimeException exception) {
                    closeQuietly(redisConnection);
                    shutdownQuietly(redisClient);
                    redisConnection = null;
                    redisClient = null;
                    if (valkeySnapshotCacheRequired(authorityConfig)) {
                        throw exception;
                    }
                    logger.warn(
                        "Valkey snapshot cache unavailable; using in-memory snapshot cache: {}",
                        exception.getMessage()
                    );
                }
            }

            Map<String, Object> cassandraConfig = section(section(authorityConfig, "hot-state"), "cassandra");
            String keyspace = stringValue(cassandraConfig.get("keyspace"), "fulcrum_authority");
            cassandraSession = cassandraSession(cassandraConfig, keyspace);
            CassandraAuthorityHotStateProjection hotStateReader =
                new CassandraAuthorityHotStateProjection(cassandraSession, keyspace);
            if (booleanValue(cassandraConfig.get("validate-schema"), true)) {
                hotStateReader.validateSchema();
            }

            return new HotReadResource(
                hotStateReader,
                hotStateReader,
                snapshotCacheStore,
                new CompositeHotReadResource(redisClient, redisConnection, cassandraSession)
            );
        } catch (RuntimeException exception) {
            closeQuietly(cassandraSession);
            closeQuietly(redisConnection);
            shutdownQuietly(redisClient);
            throw exception;
        }
    }

    private static boolean valkeySnapshotCacheEnabled(Map<String, Object> authorityConfig) {
        return "valkey".equalsIgnoreCase(stringValue(
            section(authorityConfig, "snapshot-cache").get("store"),
            "valkey"
        ));
    }

    private static boolean valkeySnapshotCacheRequired(Map<String, Object> authorityConfig) {
        return booleanValue(section(authorityConfig, "snapshot-cache").get("required"), true);
    }

    private RedisURI redisUri(Map<String, Object> redisConfig) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(stringValue(redisConfig.get("host"), "localhost"))
            .withPort((int) longValue(redisConfig.get("port"), 6379L))
            .withDatabase((int) longValue(redisConfig.get("database"), 0L))
            .withTimeout(Duration.ofMillis(longValue(redisConfig.get("connection-timeout"), 2000L)));
        String password = stringValue(redisConfig.get("password"), "");
        if (!password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        return builder.build();
    }

    private CqlSession cassandraSession(Map<String, Object> cassandraConfig, String keyspace) {
        var builder = CqlSession.builder()
            .withLocalDatacenter(stringValue(cassandraConfig.get("local-datacenter"), "datacenter1"))
            .withKeyspace(CqlIdentifier.fromCql(keyspace));
        for (InetSocketAddress contactPoint : cassandraContactPoints(
            stringValue(cassandraConfig.get("contact-points"), "localhost:9042")
        )) {
            builder.addContactPoint(contactPoint);
        }
        String username = stringValue(cassandraConfig.get("username"), "");
        String password = stringValue(cassandraConfig.get("password"), "");
        if (!username.isBlank()) {
            builder.withAuthCredentials(username, password);
        }
        return builder.build();
    }

    private static Iterable<InetSocketAddress> cassandraContactPoints(String raw) {
        java.util.ArrayList<InetSocketAddress> contactPoints = new java.util.ArrayList<>();
        String value = raw == null || raw.isBlank() ? "localhost:9042" : raw;
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int separator = trimmed.lastIndexOf(':');
            String host = separator > 0 ? trimmed.substring(0, separator) : trimmed;
            int port = separator > 0 ? Integer.parseInt(trimmed.substring(separator + 1)) : 9042;
            contactPoints.add(new InetSocketAddress(host, port));
        }
        return contactPoints.isEmpty() ? List.of(new InetSocketAddress("localhost", 9042)) : contactPoints;
    }

    private void closeAuthorityLog() {
        if (authorityLog == null) {
            return;
        }
        try {
            authorityLog.close();
        } catch (Exception exception) {
            logger.warn("Failed to close Data Authority command log", exception);
        }
        authorityLog = null;
    }

    private void closeHotReadResource() {
        if (hotReadResource == null) {
            return;
        }
        try {
            hotReadResource.close();
        } catch (Exception exception) {
            logger.warn("Failed to close Data Authority hot-read resources", exception);
        }
        hotReadResource = null;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            // Startup is already failing; preserve the original failure path.
        }
    }

    private static void shutdownQuietly(RedisClient client) {
        if (client == null) {
            return;
        }
        try {
            client.shutdown();
        } catch (RuntimeException exception) {
            // Startup is already failing; preserve the original failure path.
        }
    }

    private record HotReadResource(
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        AuthoritySnapshotCacheStore cacheStore,
        AutoCloseable resource
    )
        implements AutoCloseable {
        @Override
        public void close() throws Exception {
            resource.close();
        }
    }

    private record CompositeHotReadResource(
        RedisClient redisClient,
        StatefulRedisConnection<String, String> redisConnection,
        CqlSession cassandraSession
    ) implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                if (cassandraSession != null) {
                    cassandraSession.close();
                }
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                if (redisConnection != null && redisConnection.isOpen()) {
                    redisConnection.close();
                }
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                if (redisClient != null) {
                    redisClient.shutdown();
                }
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void subscribeSnapshotInvalidations(
        MessageBus messageBus,
        String authorityServerId,
        WatermarkedDataAuthorityCache cache
    ) {
        unsubscribeSnapshotInvalidations();
        this.messageBus = messageBus;
        this.snapshotInvalidationHandler = envelope -> {
            if (!authorityServerId.equals(envelope.getSenderId())) {
                return;
            }
            AuthoritySnapshotInvalidation.fromJson(envelope.getPayload().toString())
                .ifPresent(cache::handleInvalidation);
        };
        messageBus.subscribe(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, snapshotInvalidationHandler);
    }

    private void unsubscribeSnapshotInvalidations() {
        if (messageBus != null && snapshotInvalidationHandler != null) {
            messageBus.unsubscribe(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, snapshotInvalidationHandler);
        }
        snapshotInvalidationHandler = null;
        messageBus = null;
    }

    static void rejectLocalAuthorityMode() {
        throw new IllegalStateException(LOCAL_AUTHORITY_DISABLED_MESSAGE);
    }

    static void rejectDirectStoreConfigForRemoteAuthority(Map<String, Object> config) {
        requireStartupAttestation(config);
    }

    static GameNodeCapabilityManifest requireNegativeCapabilityManifest(Map<String, Object> config) {
        return requireStartupAttestation(config).manifest();
    }

    static GameNodeStartupAttestation.Report requireStartupAttestation(Map<String, Object> config) {
        GameNodeCapabilityManifest manifest = GameNodeCapabilityManifest.loadDefault(
            GameNodeStorageGuard.NodeKind.VELOCITY,
            VelocityDataAuthorityFeature.class.getClassLoader()
        );
        return GameNodeStartupAttestation.require(
            manifest,
            config,
            VelocityDataAuthorityFeature.class.getClassLoader()
        );
    }

    static RuntimeDataAuthorityAttestation runtimeDataAuthorityAttestation(
        GameNodeStartupAttestation.Report report,
        String mode,
        String cacheMode
    ) {
        GameNodeCapabilityManifest manifest = report.manifest();
        return new RuntimeDataAuthorityAttestation(
            manifest.nodeKind().displayName(),
            manifest.version(),
            report.passed(),
            runtimeDataMode(mode),
            normalizeDescription(cacheMode, SNAPSHOT_CACHE_MODE),
            manifest.commandSchemaVersion(),
            manifest.commandContractFingerprint(),
            manifest.readSchemaVersion(),
            manifest.readContractFingerprint(),
            report.configFingerprint(),
            report.classpathFingerprint(),
            report.attestationFingerprint()
        );
    }

    static RuntimeAuthorityDeliveryManifest runtimeAuthorityDeliveryManifest(
        GameNodeStartupAttestation.Report report,
        String mode,
        String cacheMode,
        String authorityServerId
    ) {
        GameNodeCapabilityManifest manifest = report.manifest();
        return RuntimeAuthorityDeliveryManifest.create(
            manifest.nodeKind().displayName(),
            manifest.version(),
            normalizeDescription(authorityServerId, "registry-service"),
            runtimeDataMode(mode),
            normalizeDescription(cacheMode, SNAPSHOT_CACHE_MODE),
            report.attestationFingerprint(),
            manifest.commandSchemaVersion(),
            manifest.commandContractFingerprint(),
            AuthorityCommandManifest.routeManifestFingerprint(),
            AuthorityDomainTopology.fingerprint(),
            authorityServicesByDomain(),
            authorityConsumerGroupsByDomain(),
            authorityPrincipalsByDomain(),
            manifest.readSchemaVersion(),
            manifest.readContractFingerprint(),
            AuthorityCommandManifest.domainsByDeclarationId(),
            AuthorityCommandManifest.deliveryModesByDeclarationId(),
            AuthorityCommandManifest.routePartitionKeyVectors(),
            AuthorityCommandManifest.authorityServicesByDeclarationId(),
            AuthorityCommandManifest.consumerGroupsByDeclarationId(),
            AuthorityCommandManifest.authorityPrincipalsByDeclarationId(),
            AuthorityCommandManifest.partitionCountsByDeclarationId(),
            AuthorityCommandManifest.commandTopicsByDeclarationId(),
            AuthorityCommandManifest.responseTopicsByDeclarationId(),
            AuthorityCommandManifest.eventTopicsByDeclarationId(),
            AuthorityCommandManifest.stateTopicsByDeclarationId(),
            AuthorityCommandManifest.commandLogStoresByDeclarationId(),
            AuthorityCommandManifest.hotProjectionStoresByDeclarationId(),
            AuthorityCommandManifest.historyStoresByDeclarationId(),
            AuthorityCommandManifest.cacheStoresByDeclarationId(),
            readProjectionFamiliesByType(),
            readServingStoresByType(),
            readCacheStoresByType()
        );
    }

    private void registerAuthorityServices() {
        serviceLocator.register(DataAuthority.CommandPort.class, commandPort);
        if (commandSubmissionPort != null) {
            serviceLocator.register(DataAuthority.CommandSubmissionPort.class, commandSubmissionPort);
        }
        serviceLocator.register(DataAuthority.PlayerProfileReader.class, profileReader);
        serviceLocator.register(DataAuthority.PlayerRankReader.class, rankReader);
        serviceLocator.register(RuntimeDataAuthorityAttestation.class, dataAuthorityAttestation);
        serviceLocator.register(RuntimeAuthorityDeliveryManifest.class, authorityDeliveryManifest);
    }

    private Map<String, Object> loadDatabaseConfig(Path dataDirectory) {
        Path configFile = dataDirectory.resolve("database-config.yml");
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/database-config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                        logger.info("Created default database-config.yml");
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create database-config.yml", e);
            }
        }

        try (InputStream input = Files.newInputStream(configFile)) {
            Map<String, Object> config = new Yaml().load(input);
            return config == null ? Map.of() : config;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load database-config.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static String runtimeDataMode(String mode) {
        String normalized = normalizeDescription(mode, "remote").toLowerCase(Locale.ROOT);
        return normalized.endsWith("-authority") ? normalized : normalized + "-authority";
    }

    private static String normalizeDescription(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, String> authorityServicesByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> authorityConsumerGroupsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> authorityPrincipalsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> readProjectionFamiliesByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::projectionFamily);
    }

    private static Map<String, String> readServingStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::servingStore);
    }

    private static Map<String, String> readCacheStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::cacheStore);
    }

    private static Map<String, String> domainTopologyMetadata(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityDomainTopology.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> readMetadataByType(
        Function<DataAuthorityReadContracts.ReadContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityReadContracts.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey().name(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }
}
