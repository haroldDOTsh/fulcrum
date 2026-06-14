package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.guard.GameNodeCapabilityManifest;
import sh.harold.fulcrum.api.data.guard.GameNodeStartupAttestation;
import sh.harold.fulcrum.api.data.guard.GameNodeStorageGuard;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.data.impl.authority.WatermarkedDataAuthorityCache;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusAuthorityChannels;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusDataAuthorityClient;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public class VelocityDataAuthorityFeature implements VelocityFeature {
    static final String SNAPSHOT_CACHE_MODE = "watermarked-snapshot-cache";

    private static final String LOCAL_AUTHORITY_DISABLED_MESSAGE =
        "authority.mode=local is no longer available on Velocity game nodes. Game nodes must use "
            + "authority.mode=remote so registry-service remains the durable Data Authority owner.";

    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.PlayerProfileReader profileReader;
    private DataAuthority.PlayerRankReader rankReader;
    private RuntimeDataAuthorityAttestation dataAuthorityAttestation;
    private RuntimeAuthorityDeliveryManifest authorityDeliveryManifest;
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
                initializeRemoteAuthority(authorityConfig);
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
            serviceLocator.unregister(DataAuthority.PlayerProfileReader.class);
            serviceLocator.unregister(DataAuthority.PlayerRankReader.class);
            serviceLocator.unregister(RuntimeDataAuthorityAttestation.class);
            serviceLocator.unregister(RuntimeAuthorityDeliveryManifest.class);
        }

        commandPort = null;
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

    private void initializeRemoteAuthority(Map<String, Object> authorityConfig) {
        MessageBus messageBus = serviceLocator.getRequiredService(MessageBus.class);
        String authorityServerId = stringValue(authorityConfig.get("server-id"), "registry-service");
        long timeoutMs = longValue(authorityConfig.get("request-timeout-ms"), 5000L);
        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            messageBus,
            authorityServerId,
            Duration.ofMillis(timeoutMs)
        );
        long snapshotCacheMaxAgeMs = longValue(
            authorityConfig.get("snapshot-cache-max-age-ms"),
            WatermarkedDataAuthorityCache.DEFAULT_MAX_AGE.toMillis()
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            client,
            client,
            client,
            Duration.ofMillis(snapshotCacheMaxAgeMs)
        );
        subscribeSnapshotInvalidations(messageBus, authorityServerId, cache);
        commandPort = cache;
        profileReader = cache;
        rankReader = cache;
        logger.info(
            "Using remote Data Authority via message bus server {} with watermarked snapshot cache max age {}ms",
            authorityServerId,
            snapshotCacheMaxAgeMs
        );
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
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            manifest.readSchemaVersion(),
            manifest.readContractFingerprint(),
            commandDomainsByType(),
            commandDeliveryModesByType(),
            DataAuthorityCommandContracts.routePartitionKeyVectors(),
            commandLogStoresByType(),
            commandHotProjectionStoresByType(),
            commandHistoryStoresByType(),
            commandCacheStoresByType(),
            readProjectionFamiliesByType(),
            readServingStoresByType(),
            readCacheStoresByType()
        );
    }

    private void registerAuthorityServices() {
        serviceLocator.register(DataAuthority.CommandPort.class, commandPort);
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
    private Map<String, Object> section(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static String runtimeDataMode(String mode) {
        String normalized = normalizeDescription(mode, "remote").toLowerCase(Locale.ROOT);
        return normalized.endsWith("-authority") ? normalized : normalized + "-authority";
    }

    private static String normalizeDescription(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, String> commandDomainsByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::domain);
    }

    private static Map<String, String> commandDeliveryModesByType() {
        return commandMetadataByType(contract -> contract.deliveryMode().name());
    }

    private static Map<String, String> commandLogStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::commandLogStore);
    }

    private static Map<String, String> commandHotProjectionStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::hotProjectionStore);
    }

    private static Map<String, String> commandHistoryStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::historyStore);
    }

    private static Map<String, String> commandCacheStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::cacheStore);
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

    private static Map<String, String> commandMetadataByType(
        Function<DataAuthorityCommandContracts.CommandContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityCommandContracts.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey().name(), extractor.apply(entry.getValue())));
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
