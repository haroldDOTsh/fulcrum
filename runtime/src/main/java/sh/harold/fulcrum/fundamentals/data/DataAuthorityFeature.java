package sh.harold.fulcrum.fundamentals.data;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.guard.GameNodeCapabilityManifest;
import sh.harold.fulcrum.api.data.guard.GameNodeStartupAttestation;
import sh.harold.fulcrum.api.data.guard.GameNodeStorageGuard;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogDataAuthorityClient;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityPrincipalCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityPrincipals;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;
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
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Logger;

public class DataAuthorityFeature implements PluginFeature {
    static final String SNAPSHOT_CACHE_MODE = "watermarked-snapshot-cache";
    static final String DEFAULT_COMMAND_TRANSPORT = "kafka";

    private static final String LOCAL_AUTHORITY_DISABLED_MESSAGE =
        "authority.mode=local is no longer available on Paper game nodes. Game nodes must use "
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
    private MessageBus messageBus;
    private MessageHandler snapshotInvalidationHandler;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.logger = plugin.getLogger();

        try {
            logger.info("Initializing Data Authority feature...");

            YamlConfiguration config = loadDatabaseConfig(plugin);
            GameNodeStartupAttestation.Report attestation = requireStartupAttestation(config);
            logger.info("Data Authority startup attestation passed: " + attestation.summary());
            String mode = config.getString("authority.mode", "remote");
            String authorityServerId = config.getString("authority.server-id", "registry-service");
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
                initializeRemoteAuthority(config, container);
            }

            registerAuthorityServices(container);
            logger.info("Data Authority initialized successfully");
        } catch (Exception e) {
            logger.severe("Failed to initialize Data Authority: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Data Authority", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down Data Authority feature...");

        unsubscribeSnapshotInvalidations();

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.CommandPort.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.CommandSubmissionPort.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerProfileReader.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerRankReader.class);
            ServiceLocatorImpl.getInstance().unregisterService(RuntimeDataAuthorityAttestation.class);
            ServiceLocatorImpl.getInstance().unregisterService(RuntimeAuthorityDeliveryManifest.class);
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
    public Class<?>[] getDependencies() {
        return new Class<?>[] { MessageBus.class };
    }

    private void initializeRemoteAuthority(YamlConfiguration config, DependencyContainer container) {
        MessageBus messageBus = container.get(MessageBus.class);
        String authorityServerId = config.getString("authority.server-id", "registry-service");
        long timeoutMs = config.getLong("authority.request-timeout-ms", 5000L);
        DataAuthority.CommandPort commandClient = commandClient(
            config,
            Duration.ofMillis(timeoutMs),
            commandProvenance(messageBus, authorityServerId)
        );
        long snapshotCacheMaxAgeMs = config.getLong(
            "authority.snapshot-cache-max-age-ms",
            WatermarkedDataAuthorityCache.DEFAULT_MAX_AGE.toMillis()
        );
        HotReadResource hotRead = hotReadResource(config, snapshotCacheMaxAgeMs);
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
            "Using remote Data Authority via "
                + config.getString("authority.command-transport", DEFAULT_COMMAND_TRANSPORT)
                + " command transport and cache/Cassandra hot reads through authority server "
                + authorityServerId
                + " with watermarked snapshot cache max age "
                + snapshotCacheMaxAgeMs
                + "ms"
        );
    }

    private DataAuthority.CommandPort commandClient(
        YamlConfiguration config,
        Duration timeout,
        DataAuthority.CommandProvenance transportProvenance
    ) {
        String transport = config.getString("authority.command-transport", DEFAULT_COMMAND_TRANSPORT);
        if (!"kafka".equalsIgnoreCase(transport) && !"log".equalsIgnoreCase(transport)) {
            throw new IllegalStateException(
                UNSUPPORTED_COMMAND_TRANSPORT_MESSAGE + " Unsupported value: " + transport
            );
        }

        closeAuthorityLog();
        KafkaAuthorityLog kafkaLog = new KafkaAuthorityLog(kafkaProperties(config));
        if (config.getBoolean("authority.kafka.create-missing-topics", false)) {
            kafkaLog.createMissingTopics();
        }
        if (config.getBoolean("authority.kafka.validate-topology", true)) {
            kafkaLog.validateTopology();
        }
        authorityLog = kafkaLog;
        return new AuthorityLogDataAuthorityClient(kafkaLog, timeout, transportProvenance);
    }

    private static DataAuthority.CommandProvenance commandProvenance(
        MessageBus messageBus,
        String authorityServerId
    ) {
        String originNode = normalizeDescription(messageBus.currentServerId(), "unknown");
        return new DataAuthority.CommandProvenance(
            originNode,
            "kafka:" + originNode + "->" + normalizeDescription(authorityServerId, "authority"),
            AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityPrincipals.nodePrincipal(originNode)
        );
    }

    private static Properties kafkaProperties(YamlConfiguration config) {
        Properties properties = new Properties();
        ConfigurationSection kafka = config.getConfigurationSection("authority.kafka");
        properties.put(
            "bootstrap.servers",
            kafka == null ? "localhost:9092" : kafka.getString("bootstrap-servers", "localhost:9092")
        );
        if (kafka == null) {
            return properties;
        }
        for (String key : kafka.getKeys(false)) {
            Object value = kafka.get(key);
            if (value == null || value instanceof ConfigurationSection) {
                continue;
            }
            if ("create-missing-topics".equals(key) || "validate-topology".equals(key)) {
                continue;
            }
            properties.put(kafkaPropertyName(key), value.toString());
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

    private HotReadResource hotReadResource(YamlConfiguration config, long snapshotCacheMaxAgeMs) {
        closeHotReadResource();
        RedisClient redisClient = null;
        StatefulRedisConnection<String, String> redisConnection = null;
        CqlSession cassandraSession = null;
        try {
            AuthoritySnapshotCacheStore snapshotCacheStore = new InMemoryAuthoritySnapshotCacheStore();
            if (valkeySnapshotCacheEnabled(config)) {
                try {
                    redisClient = RedisClient.create(redisUri(config));
                    redisConnection = redisClient.connect();
                    redisConnection.sync().ping();
                    snapshotCacheStore = new ValkeyAuthoritySnapshotCacheStore(
                        redisConnection.sync(),
                        Duration.ofMillis(snapshotCacheMaxAgeMs)
                    );
                } catch (RuntimeException exception) {
                    closeQuietly(redisConnection, "Valkey snapshot-cache connection");
                    shutdownQuietly(redisClient, "Valkey snapshot-cache client");
                    redisConnection = null;
                    redisClient = null;
                    if (valkeySnapshotCacheRequired(config)) {
                        throw exception;
                    }
                    logger.warning(
                        "Valkey snapshot cache unavailable; using in-memory snapshot cache: "
                            + exception.getMessage()
                    );
                }
            }

            String keyspace = config.getString("authority.hot-state.cassandra.keyspace", "fulcrum_authority");
            cassandraSession = cassandraSession(config, keyspace);
            CassandraAuthorityHotStateProjection hotStateReader =
                new CassandraAuthorityHotStateProjection(cassandraSession, keyspace);
            if (config.getBoolean("authority.hot-state.cassandra.validate-schema", true)) {
                hotStateReader.validateSchema();
            }

            return new HotReadResource(
                hotStateReader,
                hotStateReader,
                snapshotCacheStore,
                new CompositeHotReadResource(redisClient, redisConnection, cassandraSession)
            );
        } catch (RuntimeException exception) {
            closeQuietly(cassandraSession, "Cassandra hot-read session");
            closeQuietly(redisConnection, "Valkey snapshot-cache connection");
            shutdownQuietly(redisClient, "Valkey snapshot-cache client");
            throw exception;
        }
    }

    private static boolean valkeySnapshotCacheEnabled(YamlConfiguration config) {
        return "valkey".equalsIgnoreCase(config.getString("authority.snapshot-cache.store", "valkey"));
    }

    private static boolean valkeySnapshotCacheRequired(YamlConfiguration config) {
        return config.getBoolean("authority.snapshot-cache.required", true);
    }

    private static RedisURI redisUri(YamlConfiguration config) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(config.getString("redis.host", "localhost"))
            .withPort(config.getInt("redis.port", 6379))
            .withDatabase(config.getInt("redis.database", 0))
            .withTimeout(Duration.ofMillis(config.getLong("redis.connection-timeout", 2000L)));
        String password = config.getString("redis.password", "");
        if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        return builder.build();
    }

    private static CqlSession cassandraSession(YamlConfiguration config, String keyspace) {
        var builder = CqlSession.builder()
            .withLocalDatacenter(config.getString("authority.hot-state.cassandra.local-datacenter", "datacenter1"))
            .withKeyspace(CqlIdentifier.fromCql(keyspace));
        for (InetSocketAddress contactPoint : cassandraContactPoints(
            config.getString("authority.hot-state.cassandra.contact-points", "localhost:9042")
        )) {
            builder.addContactPoint(contactPoint);
        }
        String username = config.getString("authority.hot-state.cassandra.username", "");
        String password = config.getString("authority.hot-state.cassandra.password", "");
        if (username != null && !username.isBlank()) {
            builder.withAuthCredentials(username, password == null ? "" : password);
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
            logger.warning("Failed to close Data Authority command log: " + exception.getMessage());
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
            logger.warning("Failed to close Data Authority hot-read resources: " + exception.getMessage());
        }
        hotReadResource = null;
    }

    private static void closeQuietly(AutoCloseable resource, String label) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            // Startup is already failing; preserve the original failure path.
        }
    }

    private static void shutdownQuietly(RedisClient client, String label) {
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

    static void rejectDirectStoreConfigForRemoteAuthority(YamlConfiguration config) {
        requireStartupAttestation(config);
    }

    static GameNodeCapabilityManifest requireNegativeCapabilityManifest(YamlConfiguration config) {
        return requireStartupAttestation(config).manifest();
    }

    static GameNodeStartupAttestation.Report requireStartupAttestation(YamlConfiguration config) {
        GameNodeCapabilityManifest manifest = GameNodeCapabilityManifest.loadDefault(
            GameNodeStorageGuard.NodeKind.PAPER,
            DataAuthorityFeature.class.getClassLoader()
        );
        return GameNodeStartupAttestation.require(
            manifest,
            sectionMap(config),
            DataAuthorityFeature.class.getClassLoader()
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

    private void registerAuthorityServices(DependencyContainer container) {
        container.register(DataAuthority.CommandPort.class, commandPort);
        if (commandSubmissionPort != null) {
            container.register(DataAuthority.CommandSubmissionPort.class, commandSubmissionPort);
        }
        container.register(DataAuthority.PlayerProfileReader.class, profileReader);
        container.register(DataAuthority.PlayerRankReader.class, rankReader);
        container.register(RuntimeDataAuthorityAttestation.class, dataAuthorityAttestation);
        container.register(RuntimeAuthorityDeliveryManifest.class, authorityDeliveryManifest);

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(DataAuthority.CommandPort.class, commandPort);
            if (commandSubmissionPort != null) {
                ServiceLocatorImpl.getInstance().registerService(
                    DataAuthority.CommandSubmissionPort.class,
                    commandSubmissionPort
                );
            }
            ServiceLocatorImpl.getInstance().registerService(DataAuthority.PlayerProfileReader.class, profileReader);
            ServiceLocatorImpl.getInstance().registerService(DataAuthority.PlayerRankReader.class, rankReader);
            ServiceLocatorImpl.getInstance().registerService(RuntimeDataAuthorityAttestation.class, dataAuthorityAttestation);
            ServiceLocatorImpl.getInstance().registerService(RuntimeAuthorityDeliveryManifest.class, authorityDeliveryManifest);
        }
    }

    private YamlConfiguration loadDatabaseConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("database-config.yml", false);
        }
        return YamlConfiguration.loadConfiguration(configFile);
    }

    private static Map<String, Object> sectionMap(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                values.put(key, sectionMap(nested));
            } else {
                values.put(key, value);
            }
        }
        return values;
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
