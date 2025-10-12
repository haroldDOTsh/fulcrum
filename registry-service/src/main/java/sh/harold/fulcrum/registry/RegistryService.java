package sh.harold.fulcrum.registry;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
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
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.route.PlayerRoutingService;
import sh.harold.fulcrum.registry.server.ServerRegistry;
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
        commandRegistry.register("status", new StatusCommand(this));
        commandRegistry.register("clear", new ClearCommand());
        commandRegistry.register("debug", new DebugCommand(this));
        commandRegistry.register("reload", new ReloadCommand(this));
        commandRegistry.register("reregister", new ReRegistrationCommand(this, messageBus));
        commandRegistry.register("locateplayer", new LocatePlayerCommand(messageBus));
        if (slotProvisionService != null) {
            commandRegistry.register("provisionslot", new ProvisionSlotCommand(slotProvisionService));
            commandRegistry.register("provisionminigame", new ProvisionMinigameCommand(slotProvisionService));
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
}
