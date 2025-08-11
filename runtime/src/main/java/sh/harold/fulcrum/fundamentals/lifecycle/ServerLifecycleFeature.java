package sh.harold.fulcrum.fundamentals.lifecycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.lifecycle.ProxyRegistry;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ProxyAnnouncementMessage;
import sh.harold.fulcrum.api.messagebus.messages.ProxyDiscoveryRequest;
import sh.harold.fulcrum.api.messagebus.messages.ProxyDiscoveryResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest;
import sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationResponse;
import sh.harold.fulcrum.api.messagebus.messages.ServerHeartbeatMessage;
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

/**
 * Server lifecycle feature that manages server registration and heartbeat.
 * This feature runs after MessageBus (infrastructure) has been initialized.
 */
public class ServerLifecycleFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(ServerLifecycleFeature.class.getName());
    
    private JavaPlugin plugin;
    private MessageBus messageBus;
    private ProxyRegistry proxyRegistry;
    private DependencyContainer container;
    private DefaultServerIdentifier serverIdentifier;
    private BukkitRunnable heartbeatTask;
    private AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicInteger registrationAttempts = new AtomicInteger(0);
    private String serverType = "MINI";  // Default type
    private int maxCapacity = 100;
    private long startTime;
    private String environment;  // Store the environment string directly
    
    // Proxy discovery and tracking
    private final Map<String, ProxyAnnouncementMessage> knownProxies = new ConcurrentHashMap<>();
    private final AtomicReference<String> registeredProxyId = new AtomicReference<>();
    private BukkitRunnable proxyDiscoveryTask;
    
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
        
        // Try to get ProxyRegistry (optional for backwards compatibility)
        this.proxyRegistry = container.getOptional(ProxyRegistry.class).orElse(null);
        
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
        
        // Setup proxy discovery handlers
        setupProxyDiscoveryHandlers();
        
        // Schedule proxy discovery and registration after server has fully loaded
        new BukkitRunnable() {
            @Override
            public void run() {
                discoverProxies();
                // Registration will be triggered after proxy discovery
            }
        }.runTaskLater(plugin, 20L); // 1 second after server starts
        
        // Start periodic proxy discovery
        startProxyDiscoveryTask();
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
        if (registered.get()) {
            return;
        }
        
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
        
        // Check if ProxyRegistry is available
        if (proxyRegistry != null) {
            Set<String> activeProxies = proxyRegistry.getRegisteredProxies();
            
            if (!activeProxies.isEmpty()) {
                // Request approval from all proxies
                requestProxyApprovals(request, activeProxies);
            } else {
                // No active proxies, allow registration (backwards compatibility)
                LOGGER.info("No active proxies found, proceeding with registration");
                completeRegistration(request);
            }
        } else {
            // ProxyRegistry not available, use old behavior (backwards compatibility)
            LOGGER.info("ProxyRegistry not available, using legacy registration");
            // Fall back to the old discovery-based approach
            legacyRegisterWithProxy();
        }
    }
    
    /**
     * Request approval from all active proxies
     */
    private void requestProxyApprovals(ServerRegistrationRequest registrationRequest, Set<String> activeProxies) {
        LOGGER.info("Registering to " + activeProxies.size() + " proxy server(s)");
        
        List<CompletableFuture<Object>> approvalFutures = new ArrayList<>();
        
        for (String proxyId : activeProxies) {
            LOGGER.fine("Sending registration request to proxy: " + proxyId);
            CompletableFuture<Object> future = messageBus.request(
                proxyId,
                "server.registration.request",
                registrationRequest,
                PROXY_TIMEOUT
            );
            approvalFutures.add(future);
        }
        
        // Wait for all proxy responses
        CompletableFuture.allOf(approvalFutures.toArray(new CompletableFuture[0]))
            .whenComplete((result, throwable) -> {
                // Execute on Bukkit main thread
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (throwable != null) {
                            LOGGER.warning("Error during proxy approval process: " + throwable.getMessage());
                            handleRegistrationFailure(registrationRequest, "Timeout or error waiting for proxy responses");
                            return;
                        }
                        
                        boolean allApproved = true;
                        List<String> rejectionReasons = new ArrayList<>();
                        String approvedProxyId = null;
                        
                        for (CompletableFuture<Object> future : approvalFutures) {
                            try {
                                Object response = future.getNow(null);
                                if (response instanceof ServerRegistrationResponse) {
                                    ServerRegistrationResponse registrationResponse = (ServerRegistrationResponse) response;
                                    if (registrationResponse.isSuccess()) {
                                        if (approvedProxyId == null) {
                                            approvedProxyId = registrationResponse.getProxyId();
                                        }
                                    } else {
                                        allApproved = false;
                                        rejectionReasons.add(registrationResponse.getMessage() != null ?
                                            registrationResponse.getMessage() : "Unknown reason");
                                    }
                                } else {
                                    allApproved = false;
                                    rejectionReasons.add("Invalid response type from proxy");
                                }
                            } catch (Exception e) {
                                allApproved = false;
                                rejectionReasons.add("Failed to get response: " + e.getMessage());
                            }
                        }
                        
                        if (allApproved) {
                            LOGGER.info("All proxies approved server registration");
                            if (approvedProxyId != null) {
                                registeredProxyId.set(approvedProxyId);
                            }
                            completeRegistration(registrationRequest);
                        } else {
                            String reasons = String.join(", ", rejectionReasons);
                            LOGGER.warning("Not all proxies approved registration. Reasons: " + reasons);
                            handleRegistrationFailure(registrationRequest, reasons);
                        }
                    }
                }.runTask(plugin);
            });
    }
    
    /**
     * Complete the registration process after approval
     */
    private void completeRegistration(ServerRegistrationRequest registrationRequest) {
        // Send registration to message bus for other listeners
        if (messageBus != null) {
            messageBus.broadcast("server.registration", registrationRequest);
            
            // Also publish to specific proxy channels for backward compatibility
            messageBus.broadcast("proxy.server.register", registrationRequest);
        }
        
        registered.set(true);
        registrationAttempts.set(0);
        
        // Start sending heartbeats after registration
        startHeartbeat();
        
        LOGGER.info("Server registered successfully");
    }
    
    /**
     * Handle registration failure with retry logic
     */
    private void handleRegistrationFailure(ServerRegistrationRequest registrationRequest, String reason) {
        int attempts = registrationAttempts.incrementAndGet();
        
        if (attempts >= MAX_REGISTRATION_ATTEMPTS) {
            LOGGER.severe("Failed to register server after " + MAX_REGISTRATION_ATTEMPTS + " attempts. Last reason: " + reason);
            // Optionally, could still allow server to run in degraded mode
            // For now, we'll fall back to legacy registration
            LOGGER.info("Falling back to legacy registration mode");
            legacyRegisterWithProxy();
            return;
        }
        
        // Calculate exponential backoff delay
        long delaySeconds = Math.min(60, (long) Math.pow(2, attempts));
        
        LOGGER.info("Registration failed (attempt " + attempts + "/" + MAX_REGISTRATION_ATTEMPTS +
                   "). Retrying in " + delaySeconds + " seconds. Reason: " + reason);
        
        // Schedule retry with exponential backoff
        new BukkitRunnable() {
            @Override
            public void run() {
                registerServer();
            }
        }.runTaskLater(plugin, delaySeconds * 20L); // Convert seconds to ticks
    }
    
    /**
     * Legacy registration method for backwards compatibility
     */
    private void legacyRegisterWithProxy() {
        String targetProxy = selectBestProxy();
        
        if (targetProxy == null) {
            LOGGER.warning("No available proxies found, will retry...");
            scheduleRegistrationRetry();
            return;
        }
        
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
        
        LOGGER.info("Attempting legacy registration with proxy: " + targetProxy +
                   ", Type: " + serverType +
                   ", Family: " + request.getFamily() +
                   ", Role: " + request.getRole() +
                   ", Capacity: " + maxCapacity);
        
        // Send targeted registration request to specific proxy
        messageBus.send("proxy:" + targetProxy, "server.registration.request", request);
        
        // Set timeout for registration
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!registered.get()) {
                    LOGGER.warning("Server registration timed out! Retrying with different proxy...");
                    scheduleRegistrationRetry();
                }
            }
        }.runTaskLater(plugin, 100L); // 5 seconds timeout
    }
    
    private void scheduleRegistrationRetry() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!registered.get()) {
                    discoverProxies();
                    legacyRegisterWithProxy();
                }
            }
        }.runTaskLater(plugin, 100L); // Retry after 5 seconds
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
        
        if (proxyDiscoveryTask != null) {
            proxyDiscoveryTask.cancel();
        }
        
        // Send deregistration message if we have a proxy
        String proxyId = registeredProxyId.get();
        if (proxyId != null && messageBus != null) {
            ServerRegistrationRequest deregister = new ServerRegistrationRequest(
                serverIdentifier.getServerId(),
                serverType,
                maxCapacity
            );
            deregister.setFamily(serverIdentifier.getFamily());
            deregister.setRole(serverIdentifier.getFamily());
            
            messageBus.send("proxy:" + proxyId, "server.deregistration", deregister);
        }
        
        LOGGER.info("Server lifecycle shutting down");
    }
    
    // Proxy discovery methods
    
    private void setupProxyDiscoveryHandlers() {
        // Handle proxy announcements
        messageBus.subscribe("proxy.announce", envelope -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ProxyAnnouncementMessage announcement = mapper.treeToValue(envelope.getPayload(), ProxyAnnouncementMessage.class);
                handleProxyAnnouncement(announcement);
            } catch (Exception e) {
                LOGGER.warning("Failed to deserialize proxy announcement: " + e.getMessage());
            }
        });
        
        // Handle proxy discovery responses
        messageBus.subscribe("server:" + serverIdentifier.getServerId(), envelope -> {
            if (envelope.getType().equals("proxy.discovery.response")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    ProxyDiscoveryResponse response = mapper.treeToValue(envelope.getPayload(), ProxyDiscoveryResponse.class);
                    handleProxyDiscoveryResponse(response);
                } catch (Exception e) {
                    LOGGER.warning("Failed to deserialize proxy discovery response: " + e.getMessage());
                }
            }
        });
        
        // Handle registration responses from proxies
        messageBus.subscribe("server:" + serverIdentifier.getServerId(), envelope -> {
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
    }
    
    private void discoverProxies() {
        // Check ProxyRegistry first if available
        if (proxyRegistry != null) {
            Set<String> proxyIds = proxyRegistry.getRegisteredProxies();
            
            for (String proxyId : proxyIds) {
                ProxyAnnouncementMessage proxyData = proxyRegistry.getProxyData(proxyId);
                if (proxyData != null) {
                    knownProxies.put(proxyId, proxyData);
                    LOGGER.info("Discovered proxy from registry: " + proxyId);
                }
            }
        }
        
        // Also send a discovery request
        ProxyDiscoveryRequest request = new ProxyDiscoveryRequest(
            serverIdentifier.getServerId(),
            serverType
        );
        
        messageBus.broadcast("proxy.discovery", request);
        
        // If we have proxies and not registered, try to register
        if (!knownProxies.isEmpty() && !registered.get()) {
            // Update ProxyRegistry if available
            if (proxyRegistry != null) {
                for (Map.Entry<String, ProxyAnnouncementMessage> entry : knownProxies.entrySet()) {
                    proxyRegistry.registerProxy(entry.getKey(), entry.getValue());
                }
            }
            registerServer();
        }
    }
    
    private void startProxyDiscoveryTask() {
        // Periodically check for new proxies
        proxyDiscoveryTask = new BukkitRunnable() {
            @Override
            public void run() {
                discoverProxies();
            }
        };
        
        proxyDiscoveryTask.runTaskTimer(plugin, 600L, 600L); // Every 30 seconds
    }
    
    private String selectBestProxy() {
        if (knownProxies.isEmpty()) {
            // Try to get from ProxyRegistry if available
            if (proxyRegistry != null) {
                return proxyRegistry.selectBestProxy();
            }
            return null;
        }
        
        // Select proxy with lowest load percentage
        String bestProxy = null;
        double lowestLoad = 100.0;
        
        for (Map.Entry<String, ProxyAnnouncementMessage> entry : knownProxies.entrySet()) {
            ProxyAnnouncementMessage proxy = entry.getValue();
            if (proxy.hasCapacity() && proxy.getLoadPercentage() < lowestLoad) {
                lowestLoad = proxy.getLoadPercentage();
                bestProxy = entry.getKey();
            }
        }
        
        return bestProxy;
    }
    
    private void handleProxyAnnouncement(ProxyAnnouncementMessage announcement) {
        String proxyId = announcement.getProxyId();
        knownProxies.put(proxyId, announcement);
        LOGGER.info("Received proxy announcement: " + proxyId + " (load: " +
            announcement.getCurrentLoad() + "/" + announcement.getCapacity() + ")");
        
        // Update ProxyRegistry if available
        if (proxyRegistry != null) {
            proxyRegistry.registerProxy(proxyId, announcement);
        }
        
        // If we don't have a proxy yet, try to register
        if (!registered.get()) {
            registerServer();
        }
    }
    
    private void handleProxyDiscoveryResponse(ProxyDiscoveryResponse response) {
        LOGGER.info("Received proxy discovery response with " + response.getProxies().size() + " proxies");
        
        for (ProxyDiscoveryResponse.ProxyInfo proxyInfo : response.getProxies()) {
            ProxyAnnouncementMessage announcement = new ProxyAnnouncementMessage(
                proxyInfo.getProxyId(),
                proxyInfo.getAddress(),
                proxyInfo.getCapacity(),
                proxyInfo.getCurrentLoad(),
                proxyInfo.getType()
            );
            knownProxies.put(proxyInfo.getProxyId(), announcement);
        }
        
        // Update ProxyRegistry if available
        if (proxyRegistry != null) {
            for (ProxyDiscoveryResponse.ProxyInfo proxyInfo : response.getProxies()) {
                ProxyAnnouncementMessage announcement = knownProxies.get(proxyInfo.getProxyId());
                if (announcement != null) {
                    proxyRegistry.registerProxy(proxyInfo.getProxyId(), announcement);
                }
            }
        }
        
        // Try to register if not already registered
        if (!registered.get()) {
            registerServer();
        }
    }
    
    private void handleProxyRegistrationResponse(ServerRegistrationResponse response) {
        if (response.isSuccess()) {
            String proxyId = response.getProxyId();
            LOGGER.info("Successfully registered with proxy: " + proxyId);
            
            registeredProxyId.set(proxyId);
            registered.set(true);
            
            // Update server identifier if new ID was assigned
            if (response.getAssignedServerId() != null) {
                serverIdentifier.updateServerId(response.getAssignedServerId());
            }
            
            startHeartbeat();
        } else {
            LOGGER.warning("Registration rejected by proxy: " + response.getMessage());
            // Try another proxy
            scheduleRegistrationRetry();
        }
    }
}