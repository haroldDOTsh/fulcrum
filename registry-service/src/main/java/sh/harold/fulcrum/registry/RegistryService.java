package sh.harold.fulcrum.registry;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCustodyPreflight;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityCommandIngressLog;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityStateRestoreDrill;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionBudget;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.MessageBusFactory;
import sh.harold.fulcrum.registry.adapter.RegistryMessageBusAdapter;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.authority.AuthoritySubstratePreflight;
import sh.harold.fulcrum.registry.console.CommandRegistry;
import sh.harold.fulcrum.registry.console.InteractiveConsole;
import sh.harold.fulcrum.registry.console.commands.*;
import sh.harold.fulcrum.registry.coordination.InMemoryRegistryCoordinationStore;
import sh.harold.fulcrum.registry.coordination.RedisRegistryCoordinationStore;
import sh.harold.fulcrum.registry.coordination.RegistryCoordinationStore;
import sh.harold.fulcrum.registry.handler.RegistrationHandler;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.persistence.PostgresRegistryNodeSnapshotStore;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshot;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshotStore;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;
import sh.harold.fulcrum.registry.route.PlayerRoutingService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized Registry Service for managing server and proxy registrations.
 * This is a standalone Java application that coordinates all server instances.
 */
public class RegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryService.class);
    
    private final Map<String, Object> config;
    private boolean debugMode;
    private final IdAllocator idAllocator;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;
    private final RegistrationHandler registrationHandler;
    private final ScheduledExecutorService scheduler;
    private final CountDownLatch shutdownLatch;
    
    private CommandRegistry commandRegistry;
    private InteractiveConsole console;
    
    private MessageBus messageBus;
    private RegistryMessageBusAdapter messageBusAdapter;
    private MessageBusConnectionConfig messageBusConnectionConfig;
    private RegistryCoordinationStore coordinationStore;
    private PostgresConnectionBudget.Report postgresConnectionBudget = PostgresConnectionBudget.empty(0);
    private AuthorityStartupState authorityStartupState = AuthorityStartupState.disabled("not-started");
    private DispatcherStartupState authorityDispatcherStartupState = DispatcherStartupState.disabled("not-started");
    private RegistryStartupReceipt startupReceipt;
    private RegistryNodeSnapshotStore nodeSnapshotStore = RegistryNodeSnapshotStore.NOOP;
    private SlotProvisionService slotProvisionService;
    private PlayerRoutingService playerRoutingService;
    
    public RegistryService() {
        this.config = loadYamlConfig();
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.shutdownLatch = new CountDownLatch(1);
        
        // Load debug mode setting
        Map<String, Object> registryConfig = (Map<String, Object>) config.get("registry");
        this.debugMode = registryConfig != null && Boolean.TRUE.equals(registryConfig.get("debug"));
        
        // Initialize components with debug mode
        this.idAllocator = new IdAllocator(debugMode);
        this.serverRegistry = new ServerRegistry(idAllocator);
        this.proxyRegistry = new ProxyRegistry(idAllocator, debugMode);
        this.heartbeatMonitor = new HeartbeatMonitor(serverRegistry, proxyRegistry, scheduler);
        
        // Initialize registration handler with debug mode
        this.registrationHandler = new RegistrationHandler(
            serverRegistry,
            proxyRegistry,
            heartbeatMonitor,
            debugMode
        );
    }
    
    private Map<String, Object> loadYamlConfig() {
        try (InputStream inputStream = getClass().getResourceAsStream("/application.yml")) {
            if (inputStream == null) {
                LOGGER.warn("application.yml not found, using default configuration");
                return createDefaultConfig();
            }
            Yaml yaml = new Yaml();
            Map<String, Object> yamlConfig = yaml.load(inputStream);
            
            // Process environment variable substitutions
            processEnvironmentVariables(yamlConfig);
            return yamlConfig;
        } catch (Exception e) {
            LOGGER.error("Failed to load application.yml, using default configuration", e);
            return createDefaultConfig();
        }
    }
    
    private void processEnvironmentVariables(Map<String, Object> config) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() instanceof Map) {
                processEnvironmentVariables((Map<String, Object>) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                String value = (String) entry.getValue();
                if (value.startsWith("${") && value.endsWith("}")) {
                    String envVarWithDefault = value.substring(2, value.length() - 1);
                    String[] parts = envVarWithDefault.split(":", 2);
                    String envVar = parts[0];
                    String defaultValue = parts.length > 1 ? parts[1] : "";
                    entry.setValue(System.getenv(envVar) != null ? System.getenv(envVar) : defaultValue);
                }
            }
        }
    }
    
    private Map<String, Object> createDefaultConfig() {
        boolean postgresEnabled = System.getenv("POSTGRES_ENABLED") != null
            ? Boolean.parseBoolean(System.getenv("POSTGRES_ENABLED"))
            : false;
        boolean authorityEnabled = System.getenv("AUTHORITY_ENABLED") != null
            ? Boolean.parseBoolean(System.getenv("AUTHORITY_ENABLED"))
            : false;

        return Map.of(
            "redis", Map.of(
                "host", System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost",
                "port", System.getenv("REDIS_PORT") != null ? Integer.parseInt(System.getenv("REDIS_PORT")) : 6379,
                "password", System.getenv("REDIS_PASSWORD") != null ? System.getenv("REDIS_PASSWORD") : ""
            ),
            "postgres", Map.of(
                "enabled", postgresEnabled,
                "host", System.getenv("POSTGRES_HOST") != null ? System.getenv("POSTGRES_HOST") : "localhost",
                "port", System.getenv("POSTGRES_PORT") != null ? Integer.parseInt(System.getenv("POSTGRES_PORT")) : 5432,
                "database", System.getenv("POSTGRES_DATABASE") != null ? System.getenv("POSTGRES_DATABASE") : "fulcrum",
                "username", System.getenv("POSTGRES_USERNAME") != null ? System.getenv("POSTGRES_USERNAME") : "fulcrum",
                "password", System.getenv("POSTGRES_PASSWORD") != null ? System.getenv("POSTGRES_PASSWORD") : "",
                "pool", Map.of(
                    "maximum-pool-size", 2,
                    "minimum-idle", 0,
                    "connection-timeout", 5000
                ),
                "connection-budget", Map.of(
                    "max-total-pool-size", System.getenv("POSTGRES_CONNECTION_BUDGET_MAX_TOTAL") != null
                        ? Integer.parseInt(System.getenv("POSTGRES_CONNECTION_BUDGET_MAX_TOTAL"))
                        : 8,
                    "enforce", System.getenv("POSTGRES_CONNECTION_BUDGET_ENFORCE") != null
                        ? Boolean.parseBoolean(System.getenv("POSTGRES_CONNECTION_BUDGET_ENFORCE"))
                        : false
                )
            ),
            "authority", Map.of(
                "enabled", authorityEnabled,
                "migrations", Map.of(
                    "enabled", true,
                    "auto-migrate", false
                ),
                "event-dispatcher", Map.of(
                    "enabled", System.getenv("AUTHORITY_EVENT_DISPATCHER_ENABLED") != null
                        ? Boolean.parseBoolean(System.getenv("AUTHORITY_EVENT_DISPATCHER_ENABLED"))
                        : true,
                    "interval-ms", System.getenv("AUTHORITY_EVENT_DISPATCHER_INTERVAL_MS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_EVENT_DISPATCHER_INTERVAL_MS"))
                        : 1000L,
                    "batch-size", System.getenv("AUTHORITY_EVENT_DISPATCHER_BATCH_SIZE") != null
                        ? Integer.parseInt(System.getenv("AUTHORITY_EVENT_DISPATCHER_BATCH_SIZE"))
                        : 50,
                    "retry-delay-ms", System.getenv("AUTHORITY_EVENT_DISPATCHER_RETRY_DELAY_MS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_EVENT_DISPATCHER_RETRY_DELAY_MS"))
                        : 5000L
                ),
                "idempotency-cache", Map.of(
                    "enabled", System.getenv("AUTHORITY_IDEMPOTENCY_CACHE_ENABLED") != null
                        ? Boolean.parseBoolean(System.getenv("AUTHORITY_IDEMPOTENCY_CACHE_ENABLED"))
                        : true,
                    "ttl-seconds", System.getenv("AUTHORITY_IDEMPOTENCY_CACHE_TTL_SECONDS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_IDEMPOTENCY_CACHE_TTL_SECONDS"))
                        : 86_400L
                ),
                "snapshot-cache", Map.of(
                    "enabled", System.getenv("AUTHORITY_SNAPSHOT_CACHE_ENABLED") != null
                        ? Boolean.parseBoolean(System.getenv("AUTHORITY_SNAPSHOT_CACHE_ENABLED"))
                        : true,
                    "ttl-seconds", System.getenv("AUTHORITY_SNAPSHOT_CACHE_TTL_SECONDS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_SNAPSHOT_CACHE_TTL_SECONDS"))
                        : 300L
                ),
                "command-worker", Map.of(
                    "enabled", System.getenv("AUTHORITY_COMMAND_WORKER_ENABLED") != null
                        ? Boolean.parseBoolean(System.getenv("AUTHORITY_COMMAND_WORKER_ENABLED"))
                        : true,
                    "interval-ms", System.getenv("AUTHORITY_COMMAND_WORKER_INTERVAL_MS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_COMMAND_WORKER_INTERVAL_MS"))
                        : 100L,
                    "max-records-per-partition", System.getenv("AUTHORITY_COMMAND_WORKER_MAX_RECORDS") != null
                        ? Integer.parseInt(System.getenv("AUTHORITY_COMMAND_WORKER_MAX_RECORDS"))
                        : 64,
                    "poll-timeout-ms", System.getenv("AUTHORITY_COMMAND_WORKER_POLL_TIMEOUT_MS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_COMMAND_WORKER_POLL_TIMEOUT_MS"))
                        : 250L,
                    "request-timeout-ms", System.getenv("AUTHORITY_COMMAND_CLIENT_TIMEOUT_MS") != null
                        ? Long.parseLong(System.getenv("AUTHORITY_COMMAND_CLIENT_TIMEOUT_MS"))
                        : 5_000L
                ),
                "substrate", Map.of(
                    "mode", System.getenv("AUTHORITY_SUBSTRATE_MODE") != null
                        ? System.getenv("AUTHORITY_SUBSTRATE_MODE")
                        : "target",
                    "command-log", System.getenv("AUTHORITY_COMMAND_LOG_SUBSTRATE") != null
                        ? System.getenv("AUTHORITY_COMMAND_LOG_SUBSTRATE")
                        : "kafka",
                    "hot-state", System.getenv("AUTHORITY_HOT_STATE_SUBSTRATE") != null
                        ? System.getenv("AUTHORITY_HOT_STATE_SUBSTRATE")
                        : "cassandra",
                    "history", System.getenv("AUTHORITY_HISTORY_SUBSTRATE") != null
                        ? System.getenv("AUTHORITY_HISTORY_SUBSTRATE")
                        : "postgresql",
                    "cache", System.getenv("AUTHORITY_CACHE_SUBSTRATE") != null
                        ? System.getenv("AUTHORITY_CACHE_SUBSTRATE")
                        : "valkey",
                    "kafka", Map.of(
                        "bootstrap-servers", System.getenv("KAFKA_BOOTSTRAP_SERVERS") != null
                            ? System.getenv("KAFKA_BOOTSTRAP_SERVERS")
                            : "localhost:9092",
                        "client-id", System.getenv("AUTHORITY_KAFKA_CLIENT_ID") != null
                            ? System.getenv("AUTHORITY_KAFKA_CLIENT_ID")
                            : "fulcrum-authority-log"
                    ),
                    "cassandra", Map.of(
                        "contact-points", System.getenv("CASSANDRA_CONTACT_POINTS") != null
                            ? System.getenv("CASSANDRA_CONTACT_POINTS")
                            : "localhost:9042",
                        "local-datacenter", System.getenv("CASSANDRA_LOCAL_DATACENTER") != null
                            ? System.getenv("CASSANDRA_LOCAL_DATACENTER")
                            : "datacenter1",
                        "keyspace", System.getenv("AUTHORITY_CASSANDRA_KEYSPACE") != null
                            ? System.getenv("AUTHORITY_CASSANDRA_KEYSPACE")
                            : "fulcrum_authority",
                        "auto-apply-schema", System.getenv("AUTHORITY_CASSANDRA_AUTO_APPLY_SCHEMA") != null
                            ? Boolean.parseBoolean(System.getenv("AUTHORITY_CASSANDRA_AUTO_APPLY_SCHEMA"))
                            : false
                    )
                )
            ),
            "registry", Map.of(
                "heartbeat-timeout", 15,
                "check-interval", 5,
                "recycle-ids", true,
                "debug", false
            ),
            "logging", Map.of(
                "level", System.getenv("LOG_LEVEL") != null ? System.getenv("LOG_LEVEL") : "INFO"
            )
        );
    }
    
    /**
     * Start the registry service
     */
    public void start() {
        // Initialize ANSI console support for Windows
        AnsiConsole.systemInstall();
        
        displayBanner();
        LOGGER.info("Starting Fulcrum Registry Service...");
        LOGGER.info("Debug mode: {}", debugMode ? "ENABLED" : "DISABLED");
        
        // Diagnostic logging for Netty classloading issues
        diagnosNettyClassLoading();
        
        try {
            // Create MessageBus configuration from application.yml
            MessageBusConnectionConfig connectionConfig = createMessageBusConfig();
            messageBusConnectionConfig = connectionConfig;

            // Create MessageBus adapter and factory
            messageBusAdapter = new RegistryMessageBusAdapter(connectionConfig, scheduler);

            // Check Redis availability before creating MessageBus
            boolean redisAvailable = MessageBusFactory.isRedisAvailable();

            if (!redisAvailable && connectionConfig.getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
                throw new IllegalStateException("Redis message bus requested but Lettuce is not available");
            }

            messageBus = MessageBusFactory.create(messageBusAdapter);
            postgresConnectionBudget = buildPostgresConnectionBudget(config);
            logPostgresConnectionBudget(postgresConnectionBudget);
            recordExternalizedAuthorityStartup();
            nodeSnapshotStore = createNodeSnapshotStore();
            serverRegistry.setSnapshotStore(nodeSnapshotStore);
            proxyRegistry.setSnapshotStore(nodeSnapshotStore);
            RegistrySnapshotRestoreReport snapshotRestoreReport = rehydrateRegistrySnapshots();
            startupReceipt = createStartupReceipt(
                Instant.now(),
                messageBusAdapter.getServerId(),
                connectionConfig.getType().name(),
                snapshotRestoreReport,
                postgresConnectionBudget,
                authorityStartupState,
                authorityDispatcherStartupState
            );
            LOGGER.info("Registry startup receipt: {}", startupReceipt.summary());
            coordinationStore = createCoordinationStore(connectionConfig);
            slotProvisionService = new SlotProvisionService(serverRegistry, messageBus, coordinationStore);
            playerRoutingService = new PlayerRoutingService(messageBus, slotProvisionService, serverRegistry, proxyRegistry);
            playerRoutingService.initialize();
            
            // Log which implementation was created
            String busClassName = messageBus.getClass().getSimpleName();
            
            if ("InMemoryMessageBus".equals(busClassName)) {
                LOGGER.warn("Using debug-only InMemoryMessageBus; this registry cannot coordinate a real cluster");
            }
            
            // Initialize RegistrationHandler with MessageBus
            // Only call initialize() - it sets messageBus and subscribes to channels
            // Do NOT call setMessageBus() first as that's redundant
            registrationHandler.initialize(messageBus);
            
            // Configure HeartbeatMonitor with MessageBus for status change broadcasting
            heartbeatMonitor.setMessageBus(messageBus);
            
            // Start heartbeat monitoring
            heartbeatMonitor.start();
            
            // Initialize command registry and console
            initializeConsole();
            
            // Request re-registration from all servers and proxies
            requestReRegistration();
            
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            
            LOGGER.info("Registry Service started successfully");
            LOGGER.info("Type 'help' for available commands");
            
            // Keep the service running
            shutdownLatch.await();
            
        } catch (Exception e) {
            LOGGER.error("Failed to start Registry Service", e);
            System.exit(1);
        }
    }
    
    private MessageBusConnectionConfig createMessageBusConfig() {
        Map<String, Object> redisConfig = (Map<String, Object>) config.get("redis");
        String redisHost = (String) redisConfig.get("host");
        Object portObj = redisConfig.get("port");
        int redisPort = portObj instanceof Integer ? (Integer) portObj : Integer.parseInt(portObj.toString());
        String password = (String) redisConfig.get("password");
        
        // Check for message bus type configuration
        Map<String, Object> messageBusConfig = (Map<String, Object>) config.get("message-bus");
        String busType = "REDIS"; // Default to Redis for backward compatibility
        if (messageBusConfig != null) {
            busType = (String) messageBusConfig.getOrDefault("type", "REDIS");
        }
        
        MessageBusConnectionConfig.Builder builder = MessageBusConnectionConfig.builder();
        
        if ("IN_MEMORY".equalsIgnoreCase(busType)) {
            if (!debugMode) {
                throw new IllegalStateException("In-memory registry message bus is only allowed when registry.debug=true");
            }
            builder.type(MessageBusConnectionConfig.MessageBusType.IN_MEMORY);
        } else {
            builder.type(MessageBusConnectionConfig.MessageBusType.REDIS)
                   .host(redisHost)
                   .port(redisPort);
            
            if (password != null && !password.isEmpty()) {
                builder.password(password);
            }
        }
        
        return builder.build();
    }

    private RegistryCoordinationStore createCoordinationStore(MessageBusConnectionConfig connectionConfig) {
        if (connectionConfig.getType() == MessageBusConnectionConfig.MessageBusType.IN_MEMORY) {
            LOGGER.warn("Using debug-only in-memory registry coordination store");
            return new InMemoryRegistryCoordinationStore();
        }

        LOGGER.info("Using Redis registry coordination store at {}:{}",
            connectionConfig.getHost(), connectionConfig.getPort());
        return new RedisRegistryCoordinationStore(connectionConfig);
    }

    private void logPostgresConnectionBudget(PostgresConnectionBudget.Report report) {
        if (report.declarations().isEmpty()) {
            return;
        }
        if (report.accepted()) {
            LOGGER.info("PostgreSQL connection budget docket: {}", report.summary());
            return;
        }
        if (postgresConnectionBudgetEnforced(config)) {
            report.requireAccepted();
        }
        LOGGER.warn("PostgreSQL connection budget docket exceeds advisory ceiling: {}", report.summary());
    }

    private void requireDeclaredPostgresPool(PostgresConnectionBudget.Declaration declaration) {
        if (!postgresConnectionBudget.declares(declaration)) {
            throw new IllegalStateException(
                "Postgres pool has no matching connection-budget declaration: " + declaration.summary()
            );
        }
    }

    static PostgresConnectionBudget.Report buildPostgresConnectionBudget(Map<String, Object> config) {
        Map<String, Object> postgresConfig = config == null
            ? Map.of()
            : copyStringMap((Map<?, ?>) config.getOrDefault("postgres", Map.of()));
        int maxTotalPoolSize = postgresConnectionBudgetMaxTotal(postgresConfig);
        if (!booleanValue(postgresConfig.get("enabled"), false)) {
            return PostgresConnectionBudget.empty(maxTotalPoolSize);
        }

        String databaseName = stringValue(postgresConfig.get("database"), "fulcrum");
        Properties poolProperties = postgresPoolProperties(postgresConfig);
        List<PostgresConnectionBudget.Declaration> declarations = new ArrayList<>();

        declarations.add(PostgresConnectionBudget.fromPoolProperties(
            "registry-service:node-snapshots",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            "registry-" + databaseName,
            poolProperties,
            2,
            0,
            5000L
        ));

        return PostgresConnectionBudget.inspect(declarations, maxTotalPoolSize);
    }

    private static int postgresConnectionBudgetMaxTotal(Map<String, Object> postgresConfig) {
        Map<String, Object> budgetConfig = postgresConnectionBudgetConfig(postgresConfig);
        return intValue(budgetConfig.get("max-total-pool-size"), 8);
    }

    private static boolean postgresConnectionBudgetEnforced(Map<String, Object> config) {
        Map<String, Object> postgresConfig = config == null
            ? Map.of()
            : copyStringMap((Map<?, ?>) config.getOrDefault("postgres", Map.of()));
        Map<String, Object> budgetConfig = postgresConnectionBudgetConfig(postgresConfig);
        return booleanValue(budgetConfig.get("enforce"), false);
    }

    private static Map<String, Object> postgresConnectionBudgetConfig(Map<String, Object> postgresConfig) {
        if (postgresConfig == null) {
            return Map.of();
        }
        Object budgetRaw = postgresConfig.get("connection-budget");
        return budgetRaw instanceof Map<?, ?> budget ? copyStringMap(budget) : Map.of();
    }

    private RegistryNodeSnapshotStore createNodeSnapshotStore() {
        Map<String, Object> postgresConfig = (Map<String, Object>) config.get("postgres");
        if (postgresConfig == null || !booleanValue(postgresConfig.get("enabled"), false)) {
            LOGGER.warn("PostgreSQL registry snapshots disabled; registry metadata will be Redis/live-memory only");
            return RegistryNodeSnapshotStore.NOOP;
        }

        String jdbcUrl = stringValue(postgresConfig.get("jdbc-url"), null);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            String host = stringValue(postgresConfig.get("host"), "localhost");
            int port = intValue(postgresConfig.get("port"), 5432);
            String database = stringValue(postgresConfig.get("database"), "fulcrum");
            jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }

        String databaseName = stringValue(postgresConfig.get("database"), "fulcrum");
        Properties poolProperties = postgresPoolProperties(postgresConfig);

        LOGGER.info("PostgreSQL registry snapshots enabled");
        PostgresRegistryNodeSnapshotStore store = new PostgresRegistryNodeSnapshotStore(
            jdbcUrl,
            stringValue(postgresConfig.get("username"), "fulcrum"),
            stringValue(postgresConfig.get("password"), ""),
            "registry-" + databaseName,
            poolProperties
        );
        requireDeclaredPostgresPool(store.poolDeclaration());
        return store;
    }

    private RegistrySnapshotRestoreReport rehydrateRegistrySnapshots() {
        try {
            RegistrySnapshotRestoreReport report =
                restoreRegistrySnapshots(nodeSnapshotStore, serverRegistry, proxyRegistry);
            if (report.loadedSnapshots() > 0) {
                LOGGER.info(
                    "Registry snapshot restore proof: loaded={}, attested={}, invalidAttestations={}, "
                        + "restoredBackends={}, backendReadbacks={}, restoredProxyReservations={}, "
                        + "proxyReservationReadbacks={}, sources={}",
                    report.loadedSnapshots(),
                    report.validAttestedSnapshots(),
                    report.invalidAttestations(),
                    report.restoredBackends(),
                    report.backendReadbacks(),
                    report.restoredProxyReservations(),
                    report.proxyReservationReadbacks(),
                    report.snapshotSources()
                );
                if (!report.readbackClean()) {
                    LOGGER.warn(
                        "Registry snapshot restore readback mismatch: restoredBackends={}, backendReadbacks={}, "
                            + "restoredProxyReservations={}, proxyReservationReadbacks={}",
                        report.restoredBackends(),
                        report.backendReadbacks(),
                        report.restoredProxyReservations(),
                        report.proxyReservationReadbacks()
                    );
                }
            }
            return report;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to rehydrate registry snapshots", exception);
        }
    }

    static RegistrySnapshotRestoreReport restoreRegistrySnapshots(
        RegistryNodeSnapshotStore nodeSnapshotStore,
        ServerRegistry serverRegistry,
        ProxyRegistry proxyRegistry
    ) {
        List<RegistryNodeSnapshot> loaded = nodeSnapshotStore.loadSnapshots();
        List<RegistryNodeSnapshot> snapshots = loaded == null ? List.of() : List.copyOf(loaded);
        int validAttestedSnapshots = 0;
        int invalidAttestations = 0;
        TreeSet<String> snapshotSources = new TreeSet<>();
        for (RegistryNodeSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            snapshotSources.add(snapshot.snapshotSource());
            if (snapshot.hasValidAttestation()) {
                validAttestedSnapshots++;
            } else {
                invalidAttestations++;
            }
        }

        int restoredBackends = serverRegistry.restoreSnapshots(snapshots);
        int restoredProxyReservations = proxyRegistry.restoreSnapshots(snapshots);
        int backendReadbacks = 0;
        int proxyReservationReadbacks = 0;
        for (RegistryNodeSnapshot snapshot : snapshots) {
            if (snapshot == null || !snapshot.permitsRestore()) {
                continue;
            }
            if (snapshot.isBackend() && serverRegistry.getServer(snapshot.nodeId()) != null) {
                backendReadbacks++;
            } else if (snapshot.isProxy() && proxyReadbackMatches(proxyRegistry, snapshot)) {
                proxyReservationReadbacks++;
            }
        }

        return new RegistrySnapshotRestoreReport(
            snapshots.size(),
            validAttestedSnapshots,
            invalidAttestations,
            restoredBackends,
            restoredProxyReservations,
            backendReadbacks,
            proxyReservationReadbacks,
            new ArrayList<>(snapshotSources),
            nodeSnapshotStore.schemaEvidence()
        );
    }

    private static boolean proxyReadbackMatches(ProxyRegistry proxyRegistry, RegistryNodeSnapshot snapshot) {
        String permanentId = proxyRegistry.getPermanentId(snapshot.nodeId());
        String addressId = proxyRegistry.getProxyIdByAddress(snapshot.address(), snapshot.port());
        return permanentId != null && permanentId.equals(addressId);
    }

    record RegistrySnapshotRestoreReport(
        int loadedSnapshots,
        int validAttestedSnapshots,
        int invalidAttestations,
        int restoredBackends,
        int restoredProxyReservations,
        int backendReadbacks,
        int proxyReservationReadbacks,
        List<String> snapshotSources,
        RegistryNodeSnapshotStore.SnapshotSchemaEvidence schemaEvidence
    ) {
        RegistrySnapshotRestoreReport {
            snapshotSources = snapshotSources == null ? List.of() : List.copyOf(snapshotSources);
            schemaEvidence = schemaEvidence == null
                ? RegistryNodeSnapshotStore.SnapshotSchemaEvidence.disabled("missing")
                : schemaEvidence;
        }

        boolean readbackClean() {
            return restoredBackends == backendReadbacks
                && restoredProxyReservations == proxyReservationReadbacks;
        }
    }

    static RegistryStartupReceipt createStartupReceipt(
        Instant createdAt,
        String registryNodeId,
        String messageBusType,
        RegistrySnapshotRestoreReport snapshotRestore,
        PostgresConnectionBudget.Report postgresConnectionBudget,
        AuthorityStartupState authority,
        DispatcherStartupState dispatcher
    ) {
        return RegistryStartupReceipt.create(
            createdAt,
            registryNodeId,
            messageBusType,
            snapshotRestore,
            postgresConnectionBudget,
            authority,
            dispatcher
        );
    }

    record RegistryStartupReceipt(
        Instant createdAt,
        String registryNodeId,
        String messageBusType,
        RegistrySnapshotRestoreReport snapshotRestore,
        PostgresConnectionBudget.Report postgresConnectionBudget,
        AuthorityStartupState authority,
        DispatcherStartupState dispatcher,
        String fingerprint
    ) {
        RegistryStartupReceipt {
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            registryNodeId = requireReceiptText(registryNodeId, "registryNodeId");
            messageBusType = requireReceiptText(messageBusType, "messageBusType");
            if (snapshotRestore == null) {
                throw new IllegalArgumentException("snapshotRestore is required");
            }
            if (postgresConnectionBudget == null) {
                throw new IllegalArgumentException("postgresConnectionBudget is required");
            }
            authority = authority == null ? AuthorityStartupState.disabled("missing") : authority;
            dispatcher = dispatcher == null ? DispatcherStartupState.disabled("missing") : dispatcher;
            fingerprint = requireReceiptText(fingerprint, "fingerprint");
        }

        static RegistryStartupReceipt create(
            Instant createdAt,
            String registryNodeId,
            String messageBusType,
            RegistrySnapshotRestoreReport snapshotRestore,
            PostgresConnectionBudget.Report postgresConnectionBudget,
            AuthorityStartupState authority,
            DispatcherStartupState dispatcher
        ) {
            AuthorityStartupState safeAuthority = authority == null
                ? AuthorityStartupState.disabled("missing")
                : authority;
            DispatcherStartupState safeDispatcher = dispatcher == null
                ? DispatcherStartupState.disabled("missing")
                : dispatcher;
            return new RegistryStartupReceipt(
                createdAt,
                registryNodeId,
                messageBusType,
                snapshotRestore,
                postgresConnectionBudget,
                safeAuthority,
                safeDispatcher,
                receiptFingerprint(
                    registryNodeId,
                    messageBusType,
                    snapshotRestore,
                    postgresConnectionBudget,
                    safeAuthority,
                    safeDispatcher
                )
            );
        }

        String summary() {
            return "fingerprint=" + fingerprint
                + ", registryNodeId=" + registryNodeId
                + ", messageBusType=" + messageBusType
                + ", snapshotRestore={loaded=" + snapshotRestore.loadedSnapshots()
                + ", readbackClean=" + snapshotRestore.readbackClean()
                + ", restoredBackends=" + snapshotRestore.restoredBackends()
                + ", backendReadbacks=" + snapshotRestore.backendReadbacks()
                + ", restoredProxyReservations=" + snapshotRestore.restoredProxyReservations()
                + ", proxyReservationReadbacks=" + snapshotRestore.proxyReservationReadbacks()
                + ", sources=" + snapshotRestore.snapshotSources()
                + ", schema=" + snapshotRestore.schemaEvidence().summary() + "}"
                + ", postgresConnectionBudget={fingerprint=" + postgresConnectionBudget.fingerprint()
                + ", accepted=" + postgresConnectionBudget.accepted()
                + ", totalDeclaredMaxPoolSize=" + postgresConnectionBudget.totalDeclaredMaxPoolSize()
                + ", maxTotalPoolSize=" + postgresConnectionBudget.maxTotalPoolSize() + "}"
                + ", authority=" + authority.summary()
                + ", dispatcher=" + dispatcher.summary();
        }
    }

    record AuthorityStartupState(
        boolean enabled,
        String ownerNode,
        String principalSource,
        boolean preflightPassed,
        String commandContractFingerprint,
        String readContractFingerprint,
        String custodyFingerprint,
        String substrateMode,
        boolean substrateTargetComplete,
        String substrateCommandLog,
        String substrateHotState,
        String substrateHistory,
        String substrateCache,
        List<String> substrateLimitations,
        List<String> checkNames,
        String disabledReason
    ) {
        AuthorityStartupState {
            substrateMode = substrateMode == null || substrateMode.isBlank() ? "unknown" : substrateMode.trim();
            substrateCommandLog = substrateCommandLog == null || substrateCommandLog.isBlank()
                ? "unknown"
                : substrateCommandLog.trim();
            substrateHotState = substrateHotState == null || substrateHotState.isBlank()
                ? "unknown"
                : substrateHotState.trim();
            substrateHistory = substrateHistory == null || substrateHistory.isBlank()
                ? "unknown"
                : substrateHistory.trim();
            substrateCache = substrateCache == null || substrateCache.isBlank() ? "unknown" : substrateCache.trim();
            substrateLimitations = substrateLimitations == null ? List.of() : List.copyOf(substrateLimitations);
            checkNames = checkNames == null ? List.of() : List.copyOf(checkNames);
            disabledReason = disabledReason == null ? "" : disabledReason.trim();
        }

        static AuthorityStartupState from(DataAuthorityCustodyPreflight.Report report) {
            return from(report, null);
        }

        static AuthorityStartupState from(
            DataAuthorityCustodyPreflight.Report report,
            AuthoritySubstratePreflight.Report substrate
        ) {
            if (report == null) {
                return disabled("missing-preflight");
            }
            AuthoritySubstratePreflight.Report safeSubstrate = substrate == null
                ? AuthoritySubstratePreflight.inspect(Map.of())
                : substrate;
            return new AuthorityStartupState(
                true,
                report.ownerNode(),
                report.principalSource(),
                report.passed(),
                report.commandContractFingerprint(),
                report.readContractFingerprint(),
                report.custodyFingerprint(),
                safeSubstrate.mode(),
                safeSubstrate.targetComplete(),
                safeSubstrate.commandLog(),
                safeSubstrate.hotState(),
                safeSubstrate.history(),
                safeSubstrate.cache(),
                safeSubstrate.limitations(),
                report.checks().stream()
                    .map(DataAuthorityCustodyPreflight.CheckResult::name)
                    .sorted()
                    .toList(),
                ""
            );
        }

        static AuthorityStartupState disabled(String reason) {
            return new AuthorityStartupState(
                false,
                null,
                null,
                false,
                null,
                null,
                null,
                "unknown",
                false,
                "unknown",
                "unknown",
                "unknown",
                "unknown",
                List.of(),
                List.of(),
                reason
            );
        }

        String summary() {
            if (!enabled) {
                return "{enabled=false, disabledReason=" + disabledReason + "}";
            }
            return "{enabled=true"
                + ", ownerNode=" + ownerNode
                + ", principalSource=" + principalSource
                + ", preflightPassed=" + preflightPassed
                + ", commandContractFingerprint=" + shortReceiptFingerprint(commandContractFingerprint)
                + ", readContractFingerprint=" + shortReceiptFingerprint(readContractFingerprint)
                + ", custodyFingerprint=" + custodyFingerprint
                + ", substrate={mode=" + substrateMode
                + ", targetComplete=" + substrateTargetComplete
                + ", commandLog=" + substrateCommandLog
                + ", hotState=" + substrateHotState
                + ", history=" + substrateHistory
                + ", cache=" + substrateCache
                + ", limitations=" + substrateLimitations + "}"
                + ", checks=" + checkNames + "}";
        }
    }

    record DispatcherStartupState(
        boolean enabled,
        boolean schemaValidated,
        boolean scheduled,
        String consumerName,
        int batchSize,
        long intervalMs,
        long retryDelayMs,
        String disabledReason
    ) {
        DispatcherStartupState {
            consumerName = consumerName == null ? "" : consumerName.trim();
            disabledReason = disabledReason == null ? "" : disabledReason.trim();
        }

        static DispatcherStartupState enabled(String consumerName, int batchSize, long intervalMs, long retryDelayMs) {
            return new DispatcherStartupState(
                true,
                true,
                true,
                consumerName,
                batchSize,
                intervalMs,
                retryDelayMs,
                ""
            );
        }

        static DispatcherStartupState disabled(String reason) {
            return new DispatcherStartupState(
                false,
                false,
                false,
                null,
                0,
                0L,
                0L,
                reason
            );
        }

        String summary() {
            if (!enabled) {
                return "{enabled=false, disabledReason=" + disabledReason + "}";
            }
            return "{enabled=true"
                + ", schemaValidated=" + schemaValidated
                + ", scheduled=" + scheduled
                + ", consumerName=" + consumerName
                + ", batchSize=" + batchSize
                + ", intervalMs=" + intervalMs
                + ", retryDelayMs=" + retryDelayMs + "}";
        }
    }

    private static String receiptFingerprint(
        String registryNodeId,
        String messageBusType,
        RegistrySnapshotRestoreReport snapshotRestore,
        PostgresConnectionBudget.Report postgresConnectionBudget,
        AuthorityStartupState authority,
        DispatcherStartupState dispatcher
    ) {
        StringBuilder material = new StringBuilder()
            .append("registryNodeId=").append(receiptString(registryNodeId)).append('\n')
            .append("messageBusType=").append(receiptString(messageBusType)).append('\n')
            .append("snapshot.loadedSnapshots=").append(snapshotRestore.loadedSnapshots()).append('\n')
            .append("snapshot.validAttestedSnapshots=").append(snapshotRestore.validAttestedSnapshots()).append('\n')
            .append("snapshot.invalidAttestations=").append(snapshotRestore.invalidAttestations()).append('\n')
            .append("snapshot.restoredBackends=").append(snapshotRestore.restoredBackends()).append('\n')
            .append("snapshot.restoredProxyReservations=").append(snapshotRestore.restoredProxyReservations()).append('\n')
            .append("snapshot.backendReadbacks=").append(snapshotRestore.backendReadbacks()).append('\n')
            .append("snapshot.proxyReservationReadbacks=").append(snapshotRestore.proxyReservationReadbacks()).append('\n')
            .append("snapshot.sources=").append(snapshotRestore.snapshotSources()).append('\n')
            .append("snapshot.schema.enabled=").append(snapshotRestore.schemaEvidence().enabled()).append('\n')
            .append("snapshot.schema.contractVersion=")
            .append(snapshotRestore.schemaEvidence().contractVersion()).append('\n')
            .append("snapshot.schema.contractFingerprint=")
            .append(receiptString(snapshotRestore.schemaEvidence().contractFingerprint())).append('\n')
            .append("snapshot.schema.tableName=")
            .append(receiptString(snapshotRestore.schemaEvidence().tableName())).append('\n')
            .append("snapshot.schema.ddlOwner=")
            .append(receiptString(snapshotRestore.schemaEvidence().ddlOwner())).append('\n')
            .append("snapshot.schema.dataOwner=")
            .append(receiptString(snapshotRestore.schemaEvidence().dataOwner())).append('\n')
            .append("snapshot.schema.createdBy=")
            .append(receiptString(snapshotRestore.schemaEvidence().createdBy())).append('\n')
            .append("snapshot.schema.schemaMigrationVersion=")
            .append(receiptString(snapshotRestore.schemaEvidence().schemaMigrationVersion())).append('\n')
            .append("snapshot.schema.schemaMigrationResource=")
            .append(receiptString(snapshotRestore.schemaEvidence().schemaMigrationResource())).append('\n')
            .append("snapshot.schema.schemaMigrationReceipt=")
            .append(receiptString(snapshotRestore.schemaEvidence().schemaMigrationReceipt())).append('\n')
            .append("snapshot.schema.disabledReason=")
            .append(receiptString(snapshotRestore.schemaEvidence().disabledReason())).append('\n')
            .append("budget.fingerprint=").append(postgresConnectionBudget.fingerprint()).append('\n')
            .append("budget.accepted=").append(postgresConnectionBudget.accepted()).append('\n')
            .append("budget.totalDeclaredMaxPoolSize=")
            .append(postgresConnectionBudget.totalDeclaredMaxPoolSize()).append('\n')
            .append("budget.maxTotalPoolSize=").append(postgresConnectionBudget.maxTotalPoolSize()).append('\n')
            .append("authority.enabled=").append(authority.enabled()).append('\n')
            .append("authority.ownerNode=").append(receiptString(authority.ownerNode())).append('\n')
            .append("authority.principalSource=").append(receiptString(authority.principalSource())).append('\n')
            .append("authority.preflightPassed=").append(authority.preflightPassed()).append('\n')
            .append("authority.commandContractFingerprint=")
            .append(receiptString(authority.commandContractFingerprint())).append('\n')
            .append("authority.readContractFingerprint=")
            .append(receiptString(authority.readContractFingerprint())).append('\n')
            .append("authority.custodyFingerprint=")
            .append(receiptString(authority.custodyFingerprint())).append('\n')
            .append("authority.substrateMode=").append(receiptString(authority.substrateMode())).append('\n')
            .append("authority.substrateTargetComplete=").append(authority.substrateTargetComplete()).append('\n')
            .append("authority.substrateCommandLog=")
            .append(receiptString(authority.substrateCommandLog())).append('\n')
            .append("authority.substrateHotState=")
            .append(receiptString(authority.substrateHotState())).append('\n')
            .append("authority.substrateHistory=")
            .append(receiptString(authority.substrateHistory())).append('\n')
            .append("authority.substrateCache=")
            .append(receiptString(authority.substrateCache())).append('\n')
            .append("authority.substrateLimitations=").append(authority.substrateLimitations()).append('\n')
            .append("authority.checkNames=").append(authority.checkNames()).append('\n')
            .append("authority.disabledReason=").append(receiptString(authority.disabledReason())).append('\n')
            .append("dispatcher.enabled=").append(dispatcher.enabled()).append('\n')
            .append("dispatcher.schemaValidated=").append(dispatcher.schemaValidated()).append('\n')
            .append("dispatcher.scheduled=").append(dispatcher.scheduled()).append('\n')
            .append("dispatcher.consumerName=").append(receiptString(dispatcher.consumerName())).append('\n')
            .append("dispatcher.batchSize=").append(dispatcher.batchSize()).append('\n')
            .append("dispatcher.intervalMs=").append(dispatcher.intervalMs()).append('\n')
            .append("dispatcher.retryDelayMs=").append(dispatcher.retryDelayMs()).append('\n')
            .append("dispatcher.disabledReason=").append(receiptString(dispatcher.disabledReason())).append('\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint registry startup receipt", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            chars[index * 2] = alphabet[value >>> 4];
            chars[index * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(chars);
    }

    private static String shortReceiptFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String requireReceiptText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String receiptString(String value) {
        return value == null ? "" : value;
    }

    private void recordExternalizedAuthorityStartup() {
        Map<String, Object> authorityConfig = (Map<String, Object>) config.get("authority");
        String disabledReason = authorityStartupDisabledReason(authorityConfig);
        authorityStartupState = AuthorityStartupState.disabled(disabledReason);
        authorityDispatcherStartupState = DispatcherStartupState.disabled(disabledReason);
        if ("authority-externalized".equals(disabledReason)) {
            LOGGER.info("Central data authority startup is externalized; registry will not start an in-process authority");
        } else {
            LOGGER.info("Central data authority disabled");
        }
    }

    static String authorityStartupDisabledReason(Map<String, Object> authorityConfig) {
        return authorityEnabled(authorityConfig) ? "authority-externalized" : "authority-disabled";
    }

    private static boolean authorityEnabled(Map<String, Object> authorityConfig) {
        if (authorityConfig == null || !authorityConfig.containsKey("enabled")) {
            return false;
        }
        Object enabled = authorityConfig.get("enabled");
        if (enabled == null || enabled.toString().isBlank()) {
            return false;
        }
        return booleanValue(enabled, false);
    }

    private static Properties postgresPoolProperties(Map<String, Object> postgresConfig) {
        Properties poolProperties = new Properties();
        Object poolRaw = postgresConfig.get("pool");
        if (poolRaw instanceof Map<?, ?> pool) {
            copyPoolProperty(poolProperties, pool, "maximum-pool-size");
            copyPoolProperty(poolProperties, pool, "minimum-idle");
            copyPoolProperty(poolProperties, pool, "connection-timeout");
            copyPoolProperty(poolProperties, pool, "idle-timeout");
            copyPoolProperty(poolProperties, pool, "max-lifetime");
            copyPoolProperty(poolProperties, pool, "leak-detection-threshold");
        }
        return poolProperties;
    }

    private static Map<String, Object> copyStringMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return values;
    }

    private static void copyPoolProperty(Properties target, Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.setProperty(key, value.toString());
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            return Integer.parseInt(value.toString());
        }
        return fallback;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
    
    private void initializeConsole() {
        // Initialize command registry
        commandRegistry = new CommandRegistry();
        
        // Register commands
        commandRegistry.register("help", new HelpCommand(commandRegistry));
        commandRegistry.register("stop", new StopCommand(this));
        commandRegistry.register("proxyregistry", new ProxyRegistryCommand(proxyRegistry, heartbeatMonitor));
        commandRegistry.register("backendregistry", new BackendRegistryCommand(serverRegistry, heartbeatMonitor));
        commandRegistry.register("status", new StatusCommand(this));
        commandRegistry.register("clear", new ClearCommand());
        commandRegistry.register("debug", new DebugCommand(this));
        commandRegistry.register("reload", new ReloadCommand(this));
        commandRegistry.register("reregister", new ReRegistrationCommand(this, messageBus));
        if (slotProvisionService != null) {
            commandRegistry.register("provisionslot", new ProvisionSlotCommand(slotProvisionService));
        }
        commandRegistry.register("debugminigamepipeline", new DebugMinigamePipelineCommand(messageBus, proxyRegistry));
        
        // Start interactive console
        console = new InteractiveConsole(commandRegistry);
        console.start();
    }
    
    /**
     * Toggle debug mode on/off
     */
    public void toggleDebugMode() {
        debugMode = !debugMode;
        registrationHandler.setDebugMode(debugMode);
        idAllocator.setDebugMode(debugMode);
        proxyRegistry.setDebugMode(debugMode);
        
        // Update logger level
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(debugMode ? ch.qos.logback.classic.Level.DEBUG : ch.qos.logback.classic.Level.INFO);
        
        LOGGER.info("Debug mode {}", debugMode ? "ENABLED" : "DISABLED");
    }
    
    /**
     * Request re-registration from all servers and proxies
     * This is called on Registry startup to recover state
     */
    private void requestReRegistration() {
        try {
            // Wait longer (10 seconds) before requesting re-registration to avoid duplicates
            // This gives proxies time to register naturally on startup
            scheduler.schedule(() -> {
                LOGGER.info("==================================================");
                LOGGER.info("Requesting re-registration from all servers/proxies");
                LOGGER.info("==================================================");
                
                // Log current registry state
                LOGGER.info("Current registry state before re-registration request:");
                LOGGER.info("  - Registered proxies: {}", proxyRegistry.getAllProxies().size());
                LOGGER.info("  - Registered servers: {}", serverRegistry.getAllServers().size());
                LOGGER.info("  - Grace period: 10 seconds");
                
                // Create re-registration request with grace period info
                Map<String, Object> request = Map.of(
                    "timestamp", System.currentTimeMillis(),
                    "reason", "Registry Service started/restarted",
                    "forceReregistration", true,
                    "graceStartTime", System.currentTimeMillis() - 10000 // Include when the registry started
                );
                
                // Broadcast on a special channel that all servers and proxies listen to
                messageBus.broadcast(ChannelConstants.REGISTRY_REREGISTRATION_REQUEST, request);
                LOGGER.info("Broadcast re-registration request to all nodes (after 10 second grace period)");
                
            }, 10, TimeUnit.SECONDS); // Increased from 2 to 10 seconds
        } catch (Exception e) {
            LOGGER.error("Failed to request re-registration", e);
        }
    }
    
    /**
     * Reload configuration from application.yml
     */
    public void reloadConfiguration() {
        LOGGER.info("Reloading configuration...");

        try {
            Map<String, Object> newConfig = loadYamlConfig();
            
            // Update debug mode
            Map<String, Object> registryConfig = (Map<String, Object>) newConfig.get("registry");
            boolean newDebugMode = registryConfig != null && Boolean.TRUE.equals(registryConfig.get("debug"));
            
            if (newDebugMode != debugMode) {
                debugMode = newDebugMode;
                registrationHandler.setDebugMode(debugMode);
                idAllocator.setDebugMode(debugMode);
                proxyRegistry.setDebugMode(debugMode);
                
                // Update logger level
                ch.qos.logback.classic.Logger rootLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                rootLogger.setLevel(debugMode ? ch.qos.logback.classic.Level.DEBUG : ch.qos.logback.classic.Level.INFO);
            }
            
            // Update heartbeat monitor settings if needed
            if (registryConfig != null) {
                Object timeoutObj = registryConfig.get("heartbeat-timeout");
                int timeout = timeoutObj instanceof Integer ? (Integer) timeoutObj : 15;
                // Could update heartbeat monitor settings here if needed
            }
            
            LOGGER.info("Configuration reloaded successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to reload configuration", e);
        }
    }

    public SlotProvisionService getSlotProvisionService() {
        return slotProvisionService;
    }

    /**
     * Get the central authority command ingress log, when authority mode is enabled.
     */
    public PostgresAuthorityCommandIngressLog getAuthorityCommandIngressLog() {
        return null;
    }

    /**
     * Get the central authority command port, when authority mode is enabled.
     */
    public DataAuthority.CommandPort getAuthorityCommandPort() {
        return null;
    }

    /**
     * Get the central authority state restore drill helper, when authority mode is enabled.
     */
    public PostgresAuthorityStateRestoreDrill getAuthorityStateRestoreDrill() {
        return null;
    }
    
    /**
     * Get the current status of the registry service
     */
    public String getStatus() {
        return "RUNNING";
    }

    /**
     * Return operator-facing Data Authority startup and dispatcher evidence.
     */
    public List<String> getAuthorityStatusLines() {
        return authorityStatusLines(startupReceipt, authorityStartupState, authorityDispatcherStartupState);
    }

    static List<String> authorityStatusLines(
        RegistryStartupReceipt receipt,
        AuthorityStartupState fallbackAuthority,
        DispatcherStartupState fallbackDispatcher
    ) {
        AuthorityStartupState authority = receipt == null ? fallbackAuthority : receipt.authority();
        DispatcherStartupState dispatcher = receipt == null ? fallbackDispatcher : receipt.dispatcher();
        authority = authority == null ? AuthorityStartupState.disabled("missing") : authority;
        dispatcher = dispatcher == null ? DispatcherStartupState.disabled("missing") : dispatcher;
        List<String> lines = new ArrayList<>();
        lines.add("Startup Receipt: " + (receipt == null ? "unavailable" : shortReceiptFingerprint(receipt.fingerprint())));
        if (!authority.enabled()) {
            lines.add("Enabled: false");
            lines.add("Disabled Reason: " + receiptString(authority.disabledReason()));
        } else {
            lines.add("Enabled: true");
            lines.add("Owner Node: " + receiptString(authority.ownerNode()));
            lines.add("Principal Source: " + receiptString(authority.principalSource()));
            lines.add("Preflight Passed: " + authority.preflightPassed());
            lines.add("Command Contract: " + shortReceiptFingerprint(authority.commandContractFingerprint()));
            lines.add("Read Contract: " + shortReceiptFingerprint(authority.readContractFingerprint()));
            lines.add("Custody Fingerprint: " + shortReceiptFingerprint(authority.custodyFingerprint()));
            lines.add("Substrate Mode: " + authority.substrateMode());
            lines.add("Substrate Target Complete: " + authority.substrateTargetComplete());
            lines.add("Substrate Command Log: " + authority.substrateCommandLog());
            lines.add("Substrate Hot State: " + authority.substrateHotState());
            lines.add("Substrate History: " + authority.substrateHistory());
            lines.add("Substrate Cache: " + authority.substrateCache());
            lines.add("Substrate Limitations: " + authority.substrateLimitations());
            lines.add("Checks: " + authority.checkNames());
        }
        if (!dispatcher.enabled()) {
            lines.add("Dispatcher: disabled (" + receiptString(dispatcher.disabledReason()) + ")");
        } else {
            lines.add("Dispatcher: enabled, consumer=" + dispatcher.consumerName()
                + ", batchSize=" + dispatcher.batchSize()
                + ", intervalMs=" + dispatcher.intervalMs());
        }
        return List.copyOf(lines);
    }

    RegistryStartupReceipt getStartupReceipt() {
        return startupReceipt;
    }

    /**
     * Get the server registry
     */
    public ServerRegistry getServerRegistry() {
        return serverRegistry;
    }
    
    /**
     * Get the proxy registry
     */
    public ProxyRegistry getProxyRegistry() {
        return proxyRegistry;
    }
    
    /**
     * Check if debug mode is enabled
     */
    public boolean isDebugMode() {
        return debugMode;
    }
    
    /**
     * Shutdown the registry service
     */
    public void shutdown() {
        LOGGER.info("Shutting down Registry Service...");
        
        try {
            // Stop console
            if (console != null) {
                console.stop();
            }
            
            // Stop heartbeat monitoring
            heartbeatMonitor.stop();
            
            // Shutdown proxy registry (cleanup executor)
            if (proxyRegistry != null) {
                proxyRegistry.shutdown();
            }
            
            // Shutdown registration handler
            if (registrationHandler != null) {
                registrationHandler.shutdown();
            }
            
            if (playerRoutingService != null) {
                playerRoutingService.shutdown();
            }

            if (coordinationStore != null) {
                coordinationStore.close();
            }

            if (nodeSnapshotStore != null) {
                nodeSnapshotStore.close();
            }

            // MessageBus shutdown is handled by the adapter

            // Shutdown adapter
            if (messageBusAdapter != null) {
                messageBusAdapter.shutdown();
            }
            
            // Shutdown scheduler
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            
            // Release shutdown latch
            shutdownLatch.countDown();
            
            // Uninstall ANSI console
            AnsiConsole.systemUninstall();
            
            LOGGER.info("Registry Service shut down successfully");
        } catch (Exception e) {
            LOGGER.error("Error during shutdown", e);
        }
    }

    /**
     * Diagnose Netty classloading issues
     */
    private void diagnosNettyClassLoading() {
        LOGGER.info("==================================================");
        LOGGER.info("NETTY CLASSLOADING DIAGNOSTICS");
        LOGGER.info("==================================================");
        
        try {
            // Check for DefaultPromise class
            Class<?> promiseClass = Class.forName("io.netty.util.concurrent.DefaultPromise");
            LOGGER.info("✓ DefaultPromise class loaded from: {}",
                promiseClass.getProtectionDomain().getCodeSource().getLocation());
            
            // Check for DefaultPromise$1 (anonymous inner class) - THE PROBLEMATIC CLASS
            try {
                Class<?> promise1Class = Class.forName("io.netty.util.concurrent.DefaultPromise$1");
                LOGGER.info("✓ DefaultPromise$1 class loaded from: {}",
                    promise1Class.getProtectionDomain().getCodeSource().getLocation());
            } catch (ClassNotFoundException e) {
                LOGGER.error("✗ DefaultPromise$1 (anonymous inner class) NOT FOUND!");
                LOGGER.error("  This is the EXACT error causing the service crash!");
                LOGGER.error("  This indicates Shadow JAR is not including inner classes properly");
            }
            
            // Check for inner classes
            Class<?>[] innerClasses = promiseClass.getDeclaredClasses();
            LOGGER.info("DefaultPromise inner classes found: {}", innerClasses.length);
            for (Class<?> inner : innerClasses) {
                LOGGER.info("  - {} from: {}",
                    inner.getName(),
                    inner.getProtectionDomain().getCodeSource().getLocation());
            }
            
            // Check for multiple Netty versions on classpath
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            java.util.Enumeration<java.net.URL> resources =
                classLoader.getResources("io/netty/util/concurrent/DefaultPromise.class");
            
            int count = 0;
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                LOGGER.info("Netty DefaultPromise found at: {}", url);
                count++;
            }
            
            if (count > 1) {
                LOGGER.warn("⚠ MULTIPLE Netty versions detected on classpath!");
                LOGGER.warn("  This can cause NoClassDefFoundError for inner classes");
                LOGGER.warn("  Common cause: netty-all (uber JAR) mixed with individual modules");
            }
            
            // Check which Netty JARs are on the classpath
            checkNettyJars();
            
        } catch (Exception e) {
            LOGGER.error("Failed to perform Netty diagnostics", e);
        }
        
        LOGGER.info("==================================================");
    }
    
    /**
     * Check which Netty JARs are on the classpath
     */
    private void checkNettyJars() {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            
            // Check for netty-all (the uber JAR)
            java.util.Enumeration<java.net.URL> nettyAll =
                classLoader.getResources("META-INF/maven/io.netty/netty-all/pom.properties");
            if (nettyAll.hasMoreElements()) {
                LOGGER.warn("⚠ netty-all (uber JAR) detected on classpath!");
                LOGGER.warn("  This conflicts with individual Netty modules from Lettuce");
                while (nettyAll.hasMoreElements()) {
                    LOGGER.warn("  Location: {}", nettyAll.nextElement());
                }
            }
            
            // Check for individual Netty modules
            String[] modules = {"netty-buffer", "netty-common", "netty-transport", "netty-handler", "netty-codec"};
            LOGGER.info("Individual Netty modules found:");
            for (String module : modules) {
                java.util.Enumeration<java.net.URL> moduleUrls =
                    classLoader.getResources("META-INF/maven/io.netty/" + module + "/pom.properties");
                while (moduleUrls.hasMoreElements()) {
                    LOGGER.info("  ✓ {}: {}", module, moduleUrls.nextElement());
                }
            }
            
            // Final diagnosis
            LOGGER.info("");
            LOGGER.info("DIAGNOSIS SUMMARY:");
            LOGGER.info("If both netty-all and individual modules are present,");
            LOGGER.info("this causes classloading conflicts, especially for anonymous inner classes.");
            LOGGER.info("Solution: Remove netty-all dependency and use only individual modules.");
            
        } catch (Exception e) {
            LOGGER.error("Failed to check Netty JARs", e);
        }
    }
    
    /**
     * Display the startup banner
     */
    private void displayBanner() {
        // Always use ASCII characters for maximum compatibility
        System.out.println();
        System.out.println("  ######  #     # #       ##### #####  #     # #     #");
        System.out.println("  #       #     # #      #      #    # #     # ##   ##");
        System.out.println("  #####   #     # #      #      #    # #     # # # # #");
        System.out.println("  #       #     # #      #      #####  #     # #  #  #");
        System.out.println("  #       #     # #      #      #   #  #     # #     #");
        System.out.println("  #        #####  ####### ##### #    #  #####  #     #");
        System.out.println();
        System.out.println("           Central Server Registry Service");
        System.out.println("              (c) harold.sh 2025");
        System.out.println();
        System.out.println("===============================================================");
        System.out.println();
    }
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        RegistryService service = new RegistryService();
        service.start();
    }
}

