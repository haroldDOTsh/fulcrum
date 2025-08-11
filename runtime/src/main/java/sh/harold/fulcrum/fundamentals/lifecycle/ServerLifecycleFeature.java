package sh.harold.fulcrum.fundamentals.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerHeartbeatMessage;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.fundamentals.messagebus.RedisMessageBus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Server lifecycle feature that manages server registration and heartbeat.
 * This feature runs after MessageBus (infrastructure) has been initialized.
 */
public class ServerLifecycleFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(ServerLifecycleFeature.class.getName());
    
    private JavaPlugin plugin;
    private MessageBus messageBus;
    private DefaultServerIdentifier serverIdentifier;
    private BukkitRunnable heartbeatTask;
    private AtomicBoolean registered = new AtomicBoolean(false);
    private String serverType = "MINI";  // Default type
    private int maxCapacity = 100;
    private long startTime;
    private String environment;  // Store the environment string directly
    
    @Override
    public int getPriority() {
        // Service layer - loads after infrastructure (MessageBus)
        return 30;
    }
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.startTime = System.currentTimeMillis();
        
        // Get MessageBus from container
        this.messageBus = container.get(MessageBus.class);
        if (messageBus == null) {
            throw new IllegalStateException("MessageBus not available - MessageBusFeature must initialize first");
        }
        
        // Determine server type based on RAM (MINI ≤8GB, MEGA >8GB)
        long maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long maxMemoryGB = maxMemoryMB / 1024;
        this.serverType = maxMemoryGB <= 8 ? "MINI" : "MEGA";
        LOGGER.info("Server type detected based on RAM: " + serverType + " (" + maxMemoryGB + "GB)");
        
        // Set capacity based on server type
        if (serverType.equals("MEGA")) {
            // MEGA servers: soft cap 60, hard cap 70
            this.maxCapacity = 70;  // Using hard cap for max capacity
            LOGGER.info("MEGA server capacity set: 70 players (hard cap)");
        } else {
            // MINI servers: soft cap 10, hard cap 15
            this.maxCapacity = 15;  // Using hard cap for max capacity
            LOGGER.info("MINI server capacity set: 15 players (hard cap)");
        }
        
        // Allow override from config if needed
        plugin.saveDefaultConfig();
        if (plugin.getConfig().contains("server.type")) {
            String configType = plugin.getConfig().getString("server.type");
            if (configType != null && !configType.isEmpty()) {
                LOGGER.info("Overriding server type from config: " + configType);
                this.serverType = configType;
            }
        }
        if (plugin.getConfig().contains("server.max-capacity")) {
            int configCapacity = plugin.getConfig().getInt("server.max-capacity");
            if (configCapacity > 0) {
                LOGGER.info("Overriding capacity from config: " + configCapacity);
                this.maxCapacity = configCapacity;
            }
        }
        
        // Load environment from ENVIRONMENT file
        String family = loadEnvironmentFamily();
        
        // Create temporary server identifier
        String tempId = "temp-" + UUID.randomUUID().toString().substring(0, 8);
        UUID instanceUuid = UUID.randomUUID();
        String address = plugin.getServer().getIp().isEmpty() ? "localhost" : plugin.getServer().getIp();
        int port = plugin.getServer().getPort();
        
        // Set soft cap and hard cap based on server type
        int softCap = serverType.equals("MEGA") ? 60 : 10;
        int hardCap = serverType.equals("MEGA") ? 70 : 15;
        
        this.serverIdentifier = new DefaultServerIdentifier(
            tempId,
            family,
            serverType,
            instanceUuid,
            address,
            port,
            softCap,
            hardCap
        );
        
        // Register services
        container.register(ServerIdentifier.class, serverIdentifier);
        
        LOGGER.info("Starting server with temporary ID: " + tempId);
        
        // Schedule registration after server has fully loaded
        new BukkitRunnable() {
            @Override
            public void run() {
                registerServer();
            }
        }.runTaskLater(plugin, 20L); // 1 second after server starts
    }
    
    /**
     * Loads the server family from the ENVIRONMENT file in the server root.
     * The environment string is used directly as the family/role without enum parsing.
     * @return The server family from the ENVIRONMENT file, or "game" as default
     */
    private String loadEnvironmentFamily() {
        try {
            File envFile = new File("ENVIRONMENT");
            if (!envFile.exists()) {
                LOGGER.warning("ENVIRONMENT file not found in server root, defaulting to 'game'");
                this.environment = "game";
                return "game";
            }
            
            String env = Files.readString(envFile.toPath()).trim();
            if (env.isEmpty()) {
                LOGGER.warning("ENVIRONMENT file is empty, defaulting to 'game'");
                this.environment = "game";
                return "game";
            }
            
            // Store and use the environment string directly as the family
            this.environment = env;
            LOGGER.info("Server environment loaded: " + env);
            return env;
        } catch (IOException e) {
            LOGGER.warning("Failed to read ENVIRONMENT file: " + e.getMessage());
            this.environment = "game";
            return "game";
        }
    }
    
    private void registerServer() {
        // Create registration request with all required data
        ServerRegistrationRequest request = new ServerRegistrationRequest(
            serverIdentifier.getServerId(),
            serverType,  // MINI or MEGA based on RAM
            maxCapacity   // Calculated from RAM
        );
        
        // Set family from the loaded environment
        request.setFamily(serverIdentifier.getFamily());
        
        // Set role to the same as family (they are synonymous)
        request.setRole(serverIdentifier.getFamily());
        
        // Set server address and port
        request.setAddress(serverIdentifier.getAddress());
        request.setPort(serverIdentifier.getPort());
        
        LOGGER.info("Registration details - Type: " + serverType +
                   ", Family: " + request.getFamily() +
                   ", Role: " + request.getRole() +
                   ", Capacity: " + maxCapacity);
        
        // Subscribe to registration response
        messageBus.subscribe("server.registration.response", envelope -> {
            try {
                // Deserialize the JsonNode payload to ServerRegistrationResponse
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ServerRegistrationResponse response = mapper.treeToValue(envelope.getPayload(), ServerRegistrationResponse.class);
                if (response.getTempId() != null && response.getTempId().equals(serverIdentifier.getServerId())) {
                    handleRegistrationResponse(response);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize registration response: " + e.getMessage());
            }
        });
        
        // Send registration request
        LOGGER.info("Sending registration request for server type: " + serverType);
        messageBus.broadcast("server.registration.request", request);
        
        // Set timeout for registration
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!registered.get()) {
                    LOGGER.warning("Server registration timed out after 30 seconds! Continuing with temporary ID.");
                    startHeartbeat();
                }
            }
        }.runTaskLater(plugin, 600L); // 30 seconds timeout
    }
    
    private void handleRegistrationResponse(ServerRegistrationResponse response) {
        if (response.isSuccess()) {
            String permanentId = response.getAssignedServerId();
            LOGGER.info("Server registered successfully with permanent ID: " + permanentId);
            
            // Update server identifier
            serverIdentifier.updateServerId(permanentId);
            
            // Note: MessageBus serverId is already set during initialization
            
            registered.set(true);
            startHeartbeat();
        } else {
            LOGGER.severe("Server registration failed: " + response.getMessage());
            // Continue with temporary ID
            startHeartbeat();
        }
    }
    
    private void startHeartbeat() {
        if (heartbeatTask != null) {
            return; // Already started
        }
        
        LOGGER.info("Starting heartbeat task for server: " + serverIdentifier.getServerId());
        
        heartbeatTask = new BukkitRunnable() {
            @Override
            public void run() {
                sendHeartbeat();
            }
        };
        
        // Send heartbeat every 30 seconds
        heartbeatTask.runTaskTimer(plugin, 0L, 600L);
    }
    
    private void sendHeartbeat() {
        ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage(
            serverIdentifier.getServerId(),
            serverType
        );
        
        // Set server metrics
        heartbeat.setTps(Bukkit.getTPS()[0]); // Get current TPS
        heartbeat.setPlayerCount(Bukkit.getOnlinePlayers().size());
        heartbeat.setMaxCapacity(maxCapacity);  // This is the hard cap
        heartbeat.setUptime(System.currentTimeMillis() - startTime);
        
        // Set family and role from the environment (they are the same)
        heartbeat.setFamily(serverIdentifier.getFamily());
        heartbeat.setRole(serverIdentifier.getFamily());
        
        // Add pool information if this is a pool server
        if (environment != null && environment.contains("pool")) {
            heartbeat.getAvailablePools().add(environment);
        }
        
        // Broadcast heartbeat
        messageBus.broadcast("server.heartbeat", heartbeat);
    }
    
    @Override
    public void shutdown() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        
        LOGGER.info("Server lifecycle shutting down");
    }
}