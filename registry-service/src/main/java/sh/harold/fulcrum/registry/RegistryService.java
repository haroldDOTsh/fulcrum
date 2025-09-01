package sh.harold.fulcrum.registry;

import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
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
import sh.harold.fulcrum.registry.server.ServerRegistry;

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
        
        try {
            // Create MessageBus configuration from application.yml
            MessageBusConnectionConfig connectionConfig = createMessageBusConfig();
            
            // Create MessageBus adapter and factory
            messageBusAdapter = new RegistryMessageBusAdapter(connectionConfig, scheduler);
            messageBus = MessageBusFactory.create(messageBusAdapter);
            
            // Initialize RegistrationHandler with MessageBus
            registrationHandler.setMessageBus(messageBus);
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
        commandRegistry.register("proxyregistry", new ProxyRegistryCommand(proxyRegistry));
        commandRegistry.register("backendregistry", new BackendRegistryCommand(serverRegistry));
        commandRegistry.register("status", new StatusCommand(this));
        commandRegistry.register("clear", new ClearCommand());
        commandRegistry.register("debug", new DebugCommand(this));
        commandRegistry.register("reload", new ReloadCommand(this));
        commandRegistry.register("reregister", new ReRegistrationCommand(this, messageBus));
        
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
            // Wait a moment for MessageBus to be fully ready
            scheduler.schedule(() -> {
                LOGGER.info("==================================================");
                LOGGER.info("Requesting re-registration from all servers/proxies");
                LOGGER.info("==================================================");
                
                // Create re-registration request
                Map<String, Object> request = Map.of(
                    "timestamp", System.currentTimeMillis(),
                    "reason", "Registry Service started/restarted",
                    "forceReregistration", true
                );
                
                // Broadcast on a special channel that all servers and proxies listen to
                messageBus.broadcast("registry:reregistration:request", request);
                LOGGER.info("Broadcast re-registration request to all nodes");
                
                // Also broadcast on the standard registration request channel
                messageBus.broadcast("proxy:request-registrations", request);
                LOGGER.info("Sent legacy registration request for backward compatibility");
                
            }, 2, TimeUnit.SECONDS);
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
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        RegistryService service = new RegistryService();
        service.start();
    }
}