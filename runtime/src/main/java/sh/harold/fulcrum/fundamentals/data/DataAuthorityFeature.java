package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
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
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

public class DataAuthorityFeature implements PluginFeature {
    static final String SNAPSHOT_CACHE_MODE = "watermarked-snapshot-cache";

    private static final String LOCAL_AUTHORITY_DISABLED_MESSAGE =
        "authority.mode=local is no longer available on Paper game nodes. Game nodes must use "
            + "authority.mode=remote so registry-service remains the durable Data Authority owner.";

    private Logger logger;
    private DataAuthority.CommandPort commandPort;
    private DataAuthority.PlayerProfileReader profileReader;
    private DataAuthority.PlayerRankReader rankReader;
    private RuntimeDataAuthorityAttestation dataAuthorityAttestation;
    private RuntimeAuthorityDeliveryManifest authorityDeliveryManifest;
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
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerProfileReader.class);
            ServiceLocatorImpl.getInstance().unregisterService(DataAuthority.PlayerRankReader.class);
            ServiceLocatorImpl.getInstance().unregisterService(RuntimeDataAuthorityAttestation.class);
            ServiceLocatorImpl.getInstance().unregisterService(RuntimeAuthorityDeliveryManifest.class);
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
    public Class<?>[] getDependencies() {
        return new Class<?>[] { MessageBus.class };
    }

    private void initializeRemoteAuthority(YamlConfiguration config, DependencyContainer container) {
        MessageBus messageBus = container.get(MessageBus.class);
        String authorityServerId = config.getString("authority.server-id", "registry-service");
        long timeoutMs = config.getLong("authority.request-timeout-ms", 5000L);
        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            messageBus,
            authorityServerId,
            Duration.ofMillis(timeoutMs)
        );
        long snapshotCacheMaxAgeMs = config.getLong(
            "authority.snapshot-cache-max-age-ms",
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
            "Using remote Data Authority via message bus server "
                + authorityServerId
                + " with watermarked snapshot cache max age "
                + snapshotCacheMaxAgeMs
                + "ms"
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

    private void registerAuthorityServices(DependencyContainer container) {
        container.register(DataAuthority.CommandPort.class, commandPort);
        container.register(DataAuthority.PlayerProfileReader.class, profileReader);
        container.register(DataAuthority.PlayerRankReader.class, rankReader);
        container.register(RuntimeDataAuthorityAttestation.class, dataAuthorityAttestation);
        container.register(RuntimeAuthorityDeliveryManifest.class, authorityDeliveryManifest);

        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(DataAuthority.CommandPort.class, commandPort);
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
