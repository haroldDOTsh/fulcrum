package sh.harold.fulcrum.fundamentals.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ProxyAnnouncementMessage;
import sh.harold.fulcrum.api.messagebus.messages.ProxyDiscoveryRequest;
import sh.harold.fulcrum.api.messagebus.messages.ProxyDiscoveryResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerHeartbeatMessage;
import sh.harold.fulcrum.api.messagebus.messages.ServerEvacuationRequest;
import sh.harold.fulcrum.api.messagebus.messages.ServerEvacuationResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerAnnouncementMessage;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.fundamentals.messagebus.RedisMessageBus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Server lifecycle feature that manages server registration and heartbeat.
 * This feature runs after MessageBus (infrastructure) has been initialized.
 */
public class ServerLifecycleFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(ServerLifecycleFeature.class.getName());
    
    private JavaPlugin plugin;
    private MessageBus messageBus;
    private DependencyContainer container;
    private DefaultServerIdentifier serverIdentifier;
    private BukkitRunnable heartbeatTask;
    private AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicInteger registrationAttempts = new AtomicInteger(0);
    private String serverType = "MINI";  // Default type
    private int maxCapacity = 100;
    private long startTime;
    private String environment;  // Store the environment string directly
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> registrationTimeoutTask;
    private final Map<String, ServerInfo> availableServers = new ConcurrentHashMap<>();
    
    // Proxy discovery and tracking
    private final Map<String, ProxyAnnouncementMessage> knownProxies = new ConcurrentHashMap<>();
    private final AtomicReference<String> registeredProxyId = new AtomicReference<>();
    
    // Configuration constants
    private static final int MAX_REGISTRATION_ATTEMPTS = 5;
    private static final Duration PROXY_TIMEOUT = Duration.ofSeconds(10);
    
    @Override
    public int getPriority() {
        // Service layer - loads after infrastructure (MessageBus)
        return 30;
    }
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        this.startTime = System.currentTimeMillis();
        
        // Get MessageBus from container
        this.messageBus = container.get(MessageBus.class);
        if (messageBus == null) {
            throw new IllegalStateException("MessageBus not available - MessageBusFeature must initialize first");
        }
        
        // Register BungeeCord channel for player transfers
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        
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
        
        // Server type and capacity are determined by RAM, not config
        // This ensures consistent behavior across the fleet
        
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
        
        // Setup message handlers
        setupMessageHandlers();
        
        // Schedule initial registration after server has fully loaded
        new BukkitRunnable() {
            @Override
            public void run() {
                // Send one-time registration broadcast
                sendInitialRegistration();
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
    
    /**
     * Send initial registration request to the Registry Service
     */
    private void sendInitialRegistration() {
        // Create registration request with all required data
        // Use the current server ID (which may be permanent if already registered)
        ServerRegistrationRequest request = new ServerRegistrationRequest(
            serverIdentifier.getServerId(),
            serverType,  // MINI or MEGA based on RAM
            maxCapacity   // Calculated from RAM
        );
        
        // Set role from the loaded environment (family field is deprecated)
        request.setRole(serverIdentifier.getFamily());
        
        // Set server address and port - CRITICAL for proxies to connect
        request.setAddress(serverIdentifier.getAddress());
        request.setPort(serverIdentifier.getPort());
        
        LOGGER.info("Sending registration request to Registry Service:");
        LOGGER.info("  Server ID: " + serverIdentifier.getServerId() +
                   (registered.get() ? " (permanent)" : " (temporary)"));
        LOGGER.info("  Type: " + serverType);
        LOGGER.info("  Role: " + request.getRole());
        LOGGER.info("  Address: " + request.getAddress() + ":" + request.getPort());
        LOGGER.info("  Capacity: " + maxCapacity);
        
        // Send registration request to Registry Service
        // Registry Service listens on proxy:register for compatibility
        messageBus.broadcast("proxy:register", request);
        LOGGER.info("Sent registration request to Registry Service on channel 'proxy:register'");
        
        // Schedule timeout warning if no response received
        scheduleRegistrationTimeout();
    }
    
    private void scheduleRegistrationTimeout() {
        // Cancel any existing timeout task
        if (registrationTimeoutTask != null && !registrationTimeoutTask.isDone()) {
            registrationTimeoutTask.cancel(false);
        }
        
        // Schedule new timeout task
        registrationTimeoutTask = scheduler.schedule(() -> {
            if (!registered.get()) {
                LOGGER.warning("Could not register! No confirmation received after 10 seconds. " +
                    "Potentially no proxies are available or there's a connection issue. " +
                    "[Server: " + serverIdentifier.getServerId() + ", Type: " + serverType + "]");
            }
        }, 10, TimeUnit.SECONDS);
    }
    
    private void handleRegistrationResponse(ServerRegistrationResponse response) {
        // Cancel timeout task if it exists
        if (registrationTimeoutTask != null && !registrationTimeoutTask.isDone()) {
            registrationTimeoutTask.cancel(false);
        }
        
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
        
        // Set role from the environment (family field is deprecated)
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
        
        if (registrationTimeoutTask != null && !registrationTimeoutTask.isDone()) {
            registrationTimeoutTask.cancel(false);
        }
        
        scheduler.shutdown();
        
        // Send deregistration message if we have a proxy
        String proxyId = registeredProxyId.get();
        if (proxyId != null && messageBus != null) {
            ServerRegistrationRequest deregister = new ServerRegistrationRequest(
                serverIdentifier.getServerId(),
                serverType,
                maxCapacity
            );
            deregister.setRole(serverIdentifier.getFamily());
            
            messageBus.send("proxy:" + proxyId, "server.deregistration", deregister);
        }
        
        LOGGER.info("Server lifecycle shutting down");
    }
    
    // Message handlers
    
    private void setupMessageHandlers() {
        // Handle proxy announcements (new proxy coming online)
        messageBus.subscribe("proxy:announce", envelope -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ProxyAnnouncementMessage announcement = mapper.treeToValue(envelope.getPayload(), ProxyAnnouncementMessage.class);
                handleProxyAnnouncement(announcement);
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize proxy announcement: " + e.getMessage());
            }
        });
        
        // Handle proxy request for registrations (when new proxy comes online)
        messageBus.subscribe("proxy:request-registrations", envelope -> {
            try {
                LOGGER.info("New proxy came online - sending our registration");
                // Send our registration information with current ID (permanent if already assigned)
                sendInitialRegistration();
            } catch (Exception e) {
                LOGGER.warning("Failed to handle registration request: " + e.getMessage());
            }
        });
        
        // Handle registration responses from proxies - subscribe to the correct channel
        messageBus.subscribe("server:registration:response", envelope -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ServerRegistrationResponse response = mapper.treeToValue(envelope.getPayload(), ServerRegistrationResponse.class);
                handleProxyRegistrationResponse(response);
                LOGGER.info("Received registration response on channel 'server:registration:response'");
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize registration response: " + e.getMessage());
            }
        });
        
        // Also subscribe to server-specific channel for targeted responses
        messageBus.subscribe("server:" + serverIdentifier.getServerId(), envelope -> {
            LOGGER.fine("Received message on server-specific channel: " + envelope.getType());
            if (envelope.getType().equals("server.registration.response")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    ServerRegistrationResponse response = mapper.treeToValue(envelope.getPayload(), ServerRegistrationResponse.class);
                    handleProxyRegistrationResponse(response);
                } catch (Exception e) {
                    LOGGER.warning("Failed to deserialize registration response: " + e.getMessage());
                }
            }
        });
        
        // Subscribe to evacuation requests
        messageBus.subscribe("server.evacuation.request", envelope -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ServerEvacuationRequest request = mapper.treeToValue(envelope.getPayload(), ServerEvacuationRequest.class);
                handleEvacuationRequest(request);
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize evacuation request: " + e.getMessage());
            }
        });
        
        // Subscribe to server announcements to track available servers
        messageBus.subscribe("server.announcement", envelope -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ServerAnnouncementMessage announcement = mapper.treeToValue(envelope.getPayload(), ServerAnnouncementMessage.class);
                handleServerAnnouncement(announcement);
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize server announcement: " + e.getMessage());
            }
        });
    }
    
    
    private void handleProxyAnnouncement(ProxyAnnouncementMessage announcement) {
        String proxyId = announcement.getProxyId();
        knownProxies.put(proxyId, announcement);
        LOGGER.info("=== PROXY ANNOUNCEMENT RECEIVED ===");
        LOGGER.info("Proxy ID: " + proxyId);
        LOGGER.info("Current Load: " + announcement.getCurrentLoad() + "/" + announcement.getCapacity());
        LOGGER.info("===================================");
        
        // If we haven't registered yet, send our registration
        if (!registered.get()) {
            LOGGER.info("New proxy detected, sending registration");
            sendInitialRegistration();
        }
    }
    
    
    private void handleProxyRegistrationResponse(ServerRegistrationResponse response) {
        if (response.isSuccess()) {
            String proxyId = response.getProxyId();
            LOGGER.info("Successfully registered with proxy: " + proxyId);
            
            registeredProxyId.set(proxyId);
            
            // Cancel timeout task if it exists
            if (registrationTimeoutTask != null && !registrationTimeoutTask.isDone()) {
                registrationTimeoutTask.cancel(false);
            }
            
            // Update server identifier if new ID was assigned (only if we had temporary ID)
            if (response.getAssignedServerId() != null &&
                serverIdentifier.getServerId().startsWith("temp-")) {
                String oldId = serverIdentifier.getServerId();
                serverIdentifier.updateServerId(response.getAssignedServerId());
                LOGGER.info("Server ID updated: " + oldId + " -> " + response.getAssignedServerId());
                registered.set(true);
                startHeartbeat();
            } else if (!registered.get()) {
                // First time registration
                registered.set(true);
                startHeartbeat();
            } else {
                // Already registered, just adding to new proxy
                LOGGER.info("Added to new proxy's server list (keeping existing ID: " +
                           serverIdentifier.getServerId() + ")");
            }
        } else {
            LOGGER.warning("Registration rejected by proxy: " + response.getMessage());
            // Don't retry - wait for proxy announcements
        }
    }
    
    private void handleEvacuationRequest(ServerEvacuationRequest request) {
        if (!request.getServerId().equals(serverIdentifier.getServerId())) {
            return; // Not for this server
        }
        
        LOGGER.warning("Received evacuation request: " + request.getReason());
        
        // Perform evacuation asynchronously
        CompletableFuture.runAsync(() -> {
            evacuateAllPlayers(request);
        });
    }
    
    private void evacuateAllPlayers(ServerEvacuationRequest request) {
        var players = Bukkit.getOnlinePlayers();
        int evacuatedCount = 0;
        int failedCount = 0;
        
        LOGGER.info("Starting evacuation of " + players.size() + " players...");
        
        for (Player player : players) {
            try {
                // Find target server
                String targetServer = findAvailableLobbyServer();
                if (targetServer == null) {
                    targetServer = findAnyAvailableServer();
                }
                
                if (targetServer != null) {
                    player.sendMessage("§c§lServer is shutting down! Moving you to another server...");
                    
                    // Transfer player using BungeeCord messaging
                    transferPlayer(player, targetServer);
                    
                    evacuatedCount++;
                    LOGGER.info("Evacuated player " + player.getName() + " to " + targetServer);
                } else {
                    // No available servers, disconnect player with message
                    player.kickPlayer("§c§lServer is shutting down!\n§7No available servers to transfer you to.\n§7Please reconnect in a moment.");
                    failedCount++;
                    LOGGER.warning("Failed to evacuate player " + player.getName() + " - no available servers");
                }
            } catch (Exception e) {
                failedCount++;
                LOGGER.severe("Error evacuating player " + player.getName() + ": " + e.getMessage());
                player.kickPlayer("§c§lServer is shutting down!\n§7Failed to transfer you to another server.\n§7Please reconnect in a moment.");
            }
        }
        
        // Send evacuation response
        ServerEvacuationResponse response = new ServerEvacuationResponse(
            serverIdentifier.getServerId(),
            failedCount == 0,
            evacuatedCount,
            failedCount,
            "Evacuation completed: " + evacuatedCount + " succeeded, " + failedCount + " failed"
        );
        
        messageBus.broadcast("server.evacuation.response", response);
        LOGGER.info("Evacuation completed: " + evacuatedCount + " players evacuated, " + failedCount + " failed");
    }
    
    private String findAvailableLobbyServer() {
        // Find available lobby servers from cached server announcements
        for (Map.Entry<String, ServerInfo> entry : availableServers.entrySet()) {
            ServerInfo info = entry.getValue();
            if (info.family != null && info.family.toLowerCase().contains("lobby") && "AVAILABLE".equals(info.status)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private String findAnyAvailableServer() {
        // Find any available server from cached server announcements
        for (Map.Entry<String, ServerInfo> entry : availableServers.entrySet()) {
            ServerInfo info = entry.getValue();
            if ("AVAILABLE".equals(info.status) && !entry.getKey().equals(serverIdentifier.getServerId())) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private void transferPlayer(Player player, String targetServer) {
        // Using BungeeCord messaging channel for compatibility
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(b);
                out.writeUTF("Connect");
                out.writeUTF(targetServer);
                player.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
            } catch (Exception e) {
                LOGGER.severe("Failed to send player transfer message: " + e.getMessage());
            }
        });
    }
    
    private void handleServerAnnouncement(ServerAnnouncementMessage announcement) {
        // Cache server information for evacuation purposes
        String serverId = announcement.getServerId();
        if (!serverId.equals(serverIdentifier.getServerId())) {
            availableServers.put(serverId, new ServerInfo(
                announcement.getServerType(),
                "AVAILABLE",  // Server announcements imply the server is available
                announcement.getAddress(),
                announcement.getPort(),
                announcement.getFamily()
            ));
        }
    }
    
    private static class ServerInfo {
        final String serverType;
        final String status;
        final String host;
        final int port;
        final String family;
        
        ServerInfo(String serverType, String status, String host, int port, String family) {
            this.serverType = serverType;
            this.status = status;
            this.host = host;
            this.port = port;
            this.family = family;
        }
    }
}