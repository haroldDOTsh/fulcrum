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
import sh.harold.fulcrum.api.module.ServerEnvironment;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.fundamentals.messagebus.RedisMessageBus;

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
        
        // Get server configuration from config.yml
        plugin.saveDefaultConfig();
        this.serverType = plugin.getConfig().getString("server.type", "MINI");
        this.maxCapacity = plugin.getConfig().getInt("server.max-capacity", 100);
        
        // Create temporary server identifier
        String tempId = "temp-" + UUID.randomUUID().toString().substring(0, 8);
        String family = "default";
        UUID instanceUuid = UUID.randomUUID();
        String address = plugin.getServer().getIp().isEmpty() ? "localhost" : plugin.getServer().getIp();
        int port = plugin.getServer().getPort();
        
        this.serverIdentifier = new DefaultServerIdentifier(
            tempId,
            family,
            serverType,
            instanceUuid,
            address,
            port,
            maxCapacity,
            maxCapacity
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
    
    private void registerServer() {
        // Get environment information if available
        ServerEnvironment environment = ServerEnvironment.getInstance();
        
        // Create registration request
        ServerRegistrationRequest request = new ServerRegistrationRequest(
            serverIdentifier.getServerId(),
            serverType,
            maxCapacity
        );
        
        // Add environment data if available
        if (environment != null) {
            request.setFamily(environment.getFamily());
            request.setRole(environment.getRole());
        } else {
            request.setFamily("default");
            request.setRole("game");
        }
        
        // Set server address and port
        request.setAddress(serverIdentifier.getAddress());
        request.setPort(serverIdentifier.getPort());
        
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
        heartbeat.setMaxCapacity(maxCapacity);
        heartbeat.setUptime(System.currentTimeMillis() - startTime);
        
        // Get environment information
        ServerEnvironment environment = ServerEnvironment.getInstance();
        if (environment != null) {
            heartbeat.setFamily(environment.getFamily());
            heartbeat.setRole(environment.getRole());
            
            // Add pool information if this is a pool server
            if (environment.getRole() != null && environment.getRole().contains("pool")) {
                heartbeat.getAvailablePools().add(environment.getRole());
            }
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