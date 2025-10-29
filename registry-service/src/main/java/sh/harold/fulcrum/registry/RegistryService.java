package sh.harold.fulcrum.registry;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.MessageBusFactory;
import sh.harold.fulcrum.registry.adapter.RegistryMessageBusAdapter;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.console.CommandRegistry;
import sh.harold.fulcrum.registry.console.InteractiveConsole;
import sh.harold.fulcrum.registry.console.commands.*;
import sh.harold.fulcrum.registry.handler.RegistrationHandler;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.network.NetworkConfigCache;
import sh.harold.fulcrum.registry.network.NetworkConfigManager;
import sh.harold.fulcrum.registry.network.NetworkConfigRepository;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.punishment.PunishmentRepository;
import sh.harold.fulcrum.registry.punishment.PunishmentService;
import sh.harold.fulcrum.registry.punishment.PunishmentSnapshotWriter;
import sh.harold.fulcrum.registry.rank.RankMutationService;
import sh.harold.fulcrum.registry.route.PlayerRoutingService;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.session.DeadServerSessionSweeper;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;

import java.io.InputStream;
import java.util.Map;
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
    private final IdAllocator idAllocator;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;
    private final RegistrationHandler registrationHandler;
    private final ScheduledExecutorService scheduler;
    private final CountDownLatch shutdownLatch;
    private boolean debugMode;
    private CommandRegistry commandRegistry;
    private InteractiveConsole console;

    private MessageBus messageBus;
    private RegistryMessageBusAdapter messageBusAdapter;
    private SlotProvisionService slotProvisionService;
    private PlayerRoutingService playerRoutingService;
    private sh.harold.fulcrum.registry.session.DeadServerSessionSweeper sessionSweeper;
    private RankMutationService rankMutationService;
    private PunishmentService punishmentService;
    private NetworkConfigManager networkConfigManager;

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

    /**
     * Main entry point
     */
    public static void main(String[] args) {
        RegistryService service = new RegistryService();
        service.start();
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
            } else if (entry.getValue() instanceof String value) {
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
        return Map.of(
                "redis", Map.of(
                        "host", System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost",
                        "port", System.getenv("REDIS_PORT") != null ? Integer.parseInt(System.getenv("REDIS_PORT")) : 6379,
                        "password", System.getenv("REDIS_PASSWORD") != null ? System.getenv("REDIS_PASSWORD") : ""
                ),
                "registry", Map.of(
                        "heartbeat-timeout", 15,
                        "check-interval", 5,
                        "recycle-ids", true,
                        "debug", false
                ),
                "storage", Map.of(
                        "mongodb", Map.of(
                                "connection-string", System.getenv("MONGODB_URI") != null ? System.getenv("MONGODB_URI") : "mongodb://localhost:27017",
                                "database", System.getenv("MONGODB_DATABASE") != null ? System.getenv("MONGODB_DATABASE") : "fulcrum"
                        ),
                        "postgres", Map.of(
                                "enabled", System.getenv("POSTGRES_ENABLED") == null || Boolean.parseBoolean(System.getenv("POSTGRES_ENABLED")),
                                "jdbc-url", System.getenv("POSTGRES_JDBC_URL") != null ? System.getenv("POSTGRES_JDBC_URL") : "jdbc:postgresql://localhost:5432/fulcrum",
                                "username", System.getenv("POSTGRES_USERNAME") != null ? System.getenv("POSTGRES_USERNAME") : "fulcrum",
                                "password", System.getenv("POSTGRES_PASSWORD") != null ? System.getenv("POSTGRES_PASSWORD") : "",
                                "database", System.getenv("POSTGRES_DATABASE") != null ? System.getenv("POSTGRES_DATABASE") : "fulcrum"
                        )
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

        try {
            // Create MessageBus configuration from application.yml
            MessageBusConnectionConfig connectionConfig = createMessageBusConfig();

            // Create MessageBus adapter and factory
            messageBusAdapter = new RegistryMessageBusAdapter(connectionConfig, scheduler);

            // Check Redis availability before creating MessageBus
            boolean redisAvailable = MessageBusFactory.isRedisAvailable();

            if (!redisAvailable && connectionConfig.getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
                LOGGER.error("Redis requested but Lettuce library not available!");
                LOGGER.error("This will cause the registry to use InMemoryMessageBus while other services use Redis!");
                LOGGER.error("Services will NOT be able to communicate!");
            }

            messageBus = MessageBusFactory.create(messageBusAdapter);
            slotProvisionService = new SlotProvisionService(serverRegistry, messageBus);
            playerRoutingService = new PlayerRoutingService(messageBus, slotProvisionService, serverRegistry, proxyRegistry);
            playerRoutingService.initialize();

            sessionSweeper = createSessionSweeper();
            if (sessionSweeper != null) {
                registrationHandler.addServerTimeoutListener(serverId -> {
                    try {
                        sessionSweeper.sweepAsync(serverId);
                    } catch (Exception e) {
                        LOGGER.error("Failed to schedule session sweep for {}", serverId, e);
                    }
                });
            }

            rankMutationService = createRankMutationService();
            punishmentService = createPunishmentService();
            networkConfigManager = createNetworkConfigManager();
            if (networkConfigManager != null) {
                networkConfigManager.initialize();
            }

            // Log which implementation was created
            String busClassName = messageBus.getClass().getSimpleName();

            if ("InMemoryMessageBus".equals(busClassName) &&
                    connectionConfig.getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
                LOGGER.warn("========================================================");
                LOGGER.warn("WARNING: Using InMemoryMessageBus instead of Redis!");
                LOGGER.warn("This means the registry CANNOT communicate with other services!");
                LOGGER.warn("Check that lettuce-core is in the classpath!");
                LOGGER.warn("========================================================");
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

    private void initializeConsole() {
        // Initialize command registry
        commandRegistry = new CommandRegistry();

        // Register commands
        commandRegistry.register("help", new HelpCommand(commandRegistry));
        commandRegistry.register("stop", new StopCommand(this));
        commandRegistry.register("proxyregistry", new ProxyRegistryCommand(proxyRegistry, heartbeatMonitor));
        commandRegistry.register("backendregistry", new BackendRegistryCommand(serverRegistry, heartbeatMonitor));
        commandRegistry.register("ls", new LogicalServersCommand(serverRegistry));
        commandRegistry.register("status", new StatusCommand(this));
        commandRegistry.register("clear", new ClearCommand());
        commandRegistry.register("cleardead", new ClearDeadServicesCommand(serverRegistry, proxyRegistry, heartbeatMonitor));
        commandRegistry.register("debug", new DebugCommand(this));
        commandRegistry.register("reload", new ReloadCommand(this));
        commandRegistry.register("reregister", new ReRegistrationCommand(this, messageBus));
        commandRegistry.register("locateplayer", new LocatePlayerCommand(messageBus));
        if (rankMutationService != null) {
            commandRegistry.register("rank", new RankCommand(rankMutationService));
        }
        if (slotProvisionService != null) {
            commandRegistry.register("provisionslot", new ProvisionSlotCommand(slotProvisionService));
            commandRegistry.register("provisionminigame", new ProvisionMinigameCommand(slotProvisionService, serverRegistry));
        }
        commandRegistry.register("debugminigamepipeline", new DebugMinigamePipelineCommand(messageBus, proxyRegistry));
        if (networkConfigManager != null) {
            commandRegistry.register("networkconfig", new NetworkConfigCommand(networkConfigManager));
        }

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
            if (networkConfigManager != null) {
                networkConfigManager.refreshProfiles();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to reload configuration", e);
        }
    }

    public SlotProvisionService getSlotProvisionService() {
        return slotProvisionService;
    }

    /**
     * Get the current status of the registry service
     */
    public String getStatus() {
        return "RUNNING";
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

    private NetworkConfigManager createNetworkConfigManager() {
        if (messageBus == null) {
            LOGGER.warn("MessageBus unavailable; network configuration manager disabled");
            return null;
        }

        Map<String, Object> storage = (Map<String, Object>) config.get("storage");
        if (storage == null) {
            LOGGER.warn("Storage configuration missing; network configuration manager disabled");
            return null;
        }

        Map<String, Object> mongoSection = (Map<String, Object>) storage.get("mongodb");
        if (mongoSection == null) {
            LOGGER.warn("MongoDB configuration missing; network configuration manager disabled");
            return null;
        }

        Map<String, Object> redisSection = (Map<String, Object>) config.get("redis");
        if (redisSection == null) {
            LOGGER.warn("Redis configuration missing; network configuration manager disabled");
            return null;
        }

        String connectionString = String.valueOf(mongoSection.getOrDefault("connection-string", "mongodb://localhost:27017"));
        String database = String.valueOf(mongoSection.getOrDefault("database", "fulcrum"));

        String redisHost = String.valueOf(redisSection.getOrDefault("host", "localhost"));
        int redisPort = redisSection.get("port") instanceof Number
                ? ((Number) redisSection.get("port")).intValue()
                : Integer.parseInt(String.valueOf(redisSection.getOrDefault("port", 6379)));
        String redisPassword = String.valueOf(redisSection.getOrDefault("password", ""));

        MongoConnectionAdapter adapter = null;
        try {
            adapter = new MongoConnectionAdapter(connectionString, database);
            NetworkConfigRepository repository = new NetworkConfigRepository(adapter);
            NetworkConfigCache cache = new NetworkConfigCache(redisHost, redisPort, redisPassword, LOGGER);
            return new NetworkConfigManager(repository, cache, messageBus, LOGGER);
        } catch (Exception ex) {
            LOGGER.error("Failed to initialise network configuration manager", ex);
            if (adapter != null) {
                adapter.close();
            }
            return null;
        }
    }

    private DeadServerSessionSweeper createSessionSweeper() {
        try {
            Map<String, Object> redisSection = (Map<String, Object>) config.get("redis");
            if (redisSection == null) {
                LOGGER.warn("Redis configuration missing; session sweeper disabled");
                return null;
            }

            String redisHost = String.valueOf(redisSection.getOrDefault("host", "localhost"));
            int redisPort = redisSection.get("port") instanceof Number
                    ? ((Number) redisSection.get("port")).intValue()
                    : Integer.parseInt(String.valueOf(redisSection.getOrDefault("port", 6379)));
            String redisPassword = String.valueOf(redisSection.getOrDefault("password", ""));

            DeadServerSessionSweeper.RedisConfig redisConfig = new DeadServerSessionSweeper.RedisConfig(redisHost, redisPort, redisPassword);

            Map<String, Object> storage = (Map<String, Object>) config.get("storage");
            DeadServerSessionSweeper.MongoConfig mongoConfig = null;
            DeadServerSessionSweeper.PostgresConfig postgresConfig = null;

            if (storage != null) {
                Map<String, Object> mongoSection = (Map<String, Object>) storage.get("mongodb");
                if (mongoSection != null) {
                    String connectionString = String.valueOf(mongoSection.getOrDefault("connection-string", "mongodb://localhost:27017"));
                    String database = String.valueOf(mongoSection.getOrDefault("database", "fulcrum"));
                    mongoConfig = new DeadServerSessionSweeper.MongoConfig(connectionString, database);
                }

                Map<String, Object> postgresSection = (Map<String, Object>) storage.get("postgres");
                if (postgresSection != null) {
                    boolean enabled = Boolean.parseBoolean(String.valueOf(postgresSection.getOrDefault("enabled", true)));
                    if (enabled) {
                        String jdbcUrl = String.valueOf(postgresSection.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum"));
                        String username = String.valueOf(postgresSection.getOrDefault("username", "fulcrum"));
                        String password = String.valueOf(postgresSection.getOrDefault("password", ""));
                        String database = String.valueOf(postgresSection.getOrDefault("database", "fulcrum"));
                        postgresConfig = new DeadServerSessionSweeper.PostgresConfig(true, jdbcUrl, username, password, database);
                    }
                }
            }

            return new DeadServerSessionSweeper(LOGGER, scheduler, redisConfig, mongoConfig, postgresConfig);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize session sweeper", e);
            return null;
        }
    }

    private RankMutationService createRankMutationService() {
        if (messageBus == null) {
            LOGGER.warn("MessageBus unavailable; rank mutation service disabled");
            return null;
        }
        Map<String, Object> storage = (Map<String, Object>) config.get("storage");
        if (storage == null) {
            LOGGER.warn("Storage configuration missing; rank mutation service disabled");
            return null;
        }

        Map<String, Object> mongoSection = (Map<String, Object>) storage.get("mongodb");
        if (mongoSection == null) {
            LOGGER.warn("MongoDB configuration missing; rank mutation service disabled");
            return null;
        }

        String connectionString = String.valueOf(mongoSection.getOrDefault("connection-string", "mongodb://localhost:27017"));
        String database = String.valueOf(mongoSection.getOrDefault("database", "fulcrum"));

        try {
            return new RankMutationService(messageBus, LOGGER, connectionString, database);
        } catch (Exception e) {
            LOGGER.error("Failed to initialise rank mutation service", e);
            return null;
        }
    }

    private PunishmentService createPunishmentService() {
        if (messageBus == null) {
            LOGGER.warn("MessageBus unavailable; punishment service disabled");
            return null;
        }
        Map<String, Object> storage = (Map<String, Object>) config.get("storage");
        if (storage == null) {
            LOGGER.warn("Storage configuration missing; punishment service disabled");
            return null;
        }

        Map<String, Object> postgresSection = (Map<String, Object>) storage.get("postgres");
        if (postgresSection == null || !Boolean.parseBoolean(String.valueOf(postgresSection.getOrDefault("enabled", true)))) {
            LOGGER.warn("PostgreSQL configuration missing or disabled; punishment service requires relational storage");
            return null;
        }

        Map<String, Object> mongoSection = (Map<String, Object>) storage.get("mongodb");
        if (mongoSection == null) {
            LOGGER.warn("MongoDB configuration missing; punishment service cannot update player documents");
            return null;
        }

        String jdbcUrl = String.valueOf(postgresSection.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum"));
        String username = String.valueOf(postgresSection.getOrDefault("username", "fulcrum"));
        String password = String.valueOf(postgresSection.getOrDefault("password", ""));
        String database = String.valueOf(postgresSection.getOrDefault("database", "fulcrum"));

        String connectionString = String.valueOf(mongoSection.getOrDefault("connection-string", "mongodb://localhost:27017"));
        String mongoDatabase = String.valueOf(mongoSection.getOrDefault("database", "fulcrum"));

        try {
            PostgresConnectionAdapter postgresAdapter = new PostgresConnectionAdapter(jdbcUrl, username, password, database);
            MongoConnectionAdapter mongoAdapter = new MongoConnectionAdapter(connectionString, mongoDatabase);
            DataAPI dataAPI = DataAPI.create(mongoAdapter);

            PunishmentRepository repository = new PunishmentRepository(postgresAdapter, LOGGER);
            PunishmentSnapshotWriter snapshotWriter = new PunishmentSnapshotWriter(mongoAdapter, dataAPI, LOGGER);
            return new PunishmentService(messageBus, LOGGER, repository, snapshotWriter);
        } catch (Exception ex) {
            LOGGER.error("Failed to initialise punishment service", ex);
            return null;
        }
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

            if (rankMutationService != null) {
                try {
                    rankMutationService.close();
                } catch (Exception closeEx) {
                    LOGGER.warn("Failed to close rank mutation service", closeEx);
                }
            }

            if (punishmentService != null) {
                try {
                    punishmentService.close();
                } catch (Exception closeEx) {
                    LOGGER.warn("Failed to close punishment service", closeEx);
                }
            }

            if (sessionSweeper != null) {
                try {
                    sessionSweeper.close();
                } catch (Exception sweeperClose) {
                    LOGGER.warn("Failed to close session sweeper", sweeperClose);
                }
            }

            if (networkConfigManager != null) {
                try {
                    networkConfigManager.close();
                } catch (Exception ex) {
                    LOGGER.warn("Failed to close network configuration manager", ex);
                }
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
}
