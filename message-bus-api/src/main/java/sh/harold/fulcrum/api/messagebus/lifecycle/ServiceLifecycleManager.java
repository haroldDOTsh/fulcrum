package sh.harold.fulcrum.api.messagebus.lifecycle;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.TypedMessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of a service including registration, heartbeats, and shutdown.
 * This is a centralized manager that handles all the common lifecycle operations
 * that every service needs.
 */
public class ServiceLifecycleManager {
    
    private static final Logger LOGGER = Logger.getLogger(ServiceLifecycleManager.class.getName());
    
    // Configuration constants
    private static final int MAX_REGISTRATION_ATTEMPTS = 5;
    private static final long REGISTRATION_TIMEOUT_MS = 10000; // 10 seconds
    private static final long REGISTRATION_RETRY_DELAY_MS = 5000; // 5 seconds
    private static final long HEARTBEAT_INTERVAL_MS = 2000; // 2 seconds
    private static final long HEARTBEAT_TIMEOUT_MS = 5000; // 5 seconds timeout
    
    private final MessageBus messageBus;
    private final ServiceIdentity identity;
    private final ServiceMetadata metadata;
    
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger registrationAttempts = new AtomicInteger(0);
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> registrationRetryTask;
    private CompletableFuture<Void> registrationFuture;
    
    // Callbacks
    private Consumer<String> onRegistrationSuccess;
    private Consumer<String> onRegistrationFailure;
    private Consumer<ServiceMetadata> onHeartbeat;
    private Runnable onShutdown;
    
    /**
     * Create a new service lifecycle manager.
     *
     * @param messageBus The message bus for communication
     * @param identity The service identity
     * @param metadata The service metadata
     */
    public ServiceLifecycleManager(MessageBus messageBus, ServiceIdentity identity, ServiceMetadata metadata) {
        this.messageBus = messageBus;
        this.identity = identity;
        this.metadata = metadata;
    }
    
    /**
     * Start the service lifecycle (registration and heartbeats).
     */
    public CompletableFuture<Void> start() {
        if (running.compareAndSet(false, true)) {
            LOGGER.info("Starting service lifecycle for " + identity);
            
            // Setup message handlers
            setupMessageHandlers();
            
            // Start registration process
            return register().thenRun(() -> {
                // Start heartbeat after successful registration
                startHeartbeat();
            });
        }
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Register the service with the registry.
     */
    private CompletableFuture<Void> register() {
        registrationFuture = new CompletableFuture<>();
        
        metadata.setStatus(ServiceStatus.REGISTERING);
        
        // Send registration request
        sendRegistrationRequest();
        
        // Setup retry mechanism
        registrationRetryTask = scheduler.scheduleWithFixedDelay(() -> {
            LOGGER.fine("[DEBUG] Retry task executing - registered status: " + registered.get());
            if (!registered.get()) {
                int attempts = registrationAttempts.incrementAndGet();
                if (attempts <= MAX_REGISTRATION_ATTEMPTS) {
                    LOGGER.warning(String.format("Retrying registration (attempt %d/%d) for %s (registered=%s)",
                        attempts, MAX_REGISTRATION_ATTEMPTS, identity.getServiceId(), registered.get()));
                    sendRegistrationRequest();
                } else {
                    LOGGER.severe("Failed to register after " + MAX_REGISTRATION_ATTEMPTS + " attempts");
                    registrationFuture.completeExceptionally(new RuntimeException("Registration failed"));
                    
                    if (onRegistrationFailure != null) {
                        onRegistrationFailure.accept("Max registration attempts exceeded");
                    }
                }
            } else {
                // Registration successful, complete the future
                if (registrationFuture != null && !registrationFuture.isDone()) {
                    registrationFuture.complete(null);
                }
                registrationRetryTask.cancel(false);
            }
        }, REGISTRATION_RETRY_DELAY_MS, REGISTRATION_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        
        // Add timeout
        scheduler.schedule(() -> {
            if (registrationFuture != null && !registrationFuture.isDone()) {
                registrationFuture.completeExceptionally(new TimeoutException("Registration timeout"));
                if (registrationRetryTask != null) {
                    registrationRetryTask.cancel(true);
                }
            }
        }, REGISTRATION_TIMEOUT_MS * MAX_REGISTRATION_ATTEMPTS, TimeUnit.MILLISECONDS);
        
        return registrationFuture;
    }
    
    /**
     * Send registration request to the registry.
     */
    private void sendRegistrationRequest() {
        ServerRegistrationRequest request = new ServerRegistrationRequest(
            identity.getTempId(),
            identity.getServiceType().getTypeName(),
            metadata.getMaxCapacity()
        );
        
        request.setRole(identity.getRole());
        request.setAddress(identity.getAddress());
        request.setPort(identity.getPort());
        
        LOGGER.info(String.format("Sending registration request: %s [%s:%d]",
            identity.getTempId(), identity.getAddress(), identity.getPort()));
        
        // Send to registry
        messageBus.broadcast(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, request);
    }
    
    /**
     * Setup message handlers for lifecycle events.
     */
    private void setupMessageHandlers() {
        // Handle registration response
        messageBus.subscribe(ChannelConstants.SERVER_REGISTRATION_RESPONSE, envelope -> {
            try {
                // Deserialize from JsonNode
                if (envelope.getPayload() instanceof com.fasterxml.jackson.databind.JsonNode) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode payload = (com.fasterxml.jackson.databind.JsonNode) envelope.getPayload();
                    
                    // Try to deserialize as ServerRegistrationResponse
                    try {
                        ServerRegistrationResponse response = mapper.treeToValue(payload, ServerRegistrationResponse.class);
                        if (response.getTempId() != null && response.getTempId().equals(identity.getTempId())) {
                            handleRegistrationResponse(response);
                        }
                    } catch (Exception e1) {
                        // Fallback to Map handling for backwards compatibility
                        java.util.Map<String, Object> map = mapper.treeToValue(payload, java.util.Map.class);
                        String tempId = (String) map.get("tempId");
                        if (tempId != null && tempId.equals(identity.getTempId())) {
                            // Create response from map
                            ServerRegistrationResponse response = new ServerRegistrationResponse();
                            response.setTempId(tempId);
                            response.setSuccess(Boolean.TRUE.equals(map.get("success")));
                            response.setAssignedServerId((String) map.get("assignedServerId"));
                            response.setMessage((String) map.get("message"));
                            handleRegistrationResponse(response);
                        }
                    }
                } else if (envelope.getPayload() instanceof java.util.Map) {
                    // Direct Map handling
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = (java.util.Map<String, Object>) envelope.getPayload();
                    String tempId = (String) map.get("tempId");
                    if (tempId != null && tempId.equals(identity.getTempId())) {
                        ServerRegistrationResponse response = new ServerRegistrationResponse();
                        response.setTempId(tempId);
                        response.setSuccess(Boolean.TRUE.equals(map.get("success")));
                        response.setAssignedServerId((String) map.get("assignedServerId"));
                        response.setMessage((String) map.get("message"));
                        handleRegistrationResponse(response);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to handle registration response", e);
            }
        });
        
        // Handle re-registration requests from registry
        messageBus.subscribe(ChannelConstants.REGISTRY_REREGISTRATION_REQUEST, envelope -> {
            LOGGER.info("Registry requested re-registration");
            sendRegistrationRequest();
            if (registered.get()) {
                sendHeartbeat(); // Send immediate heartbeat if already registered
            }
        });
        
        // Handle targeted re-registration request
        String reregisterChannel = ChannelConstants.getServerReregisterChannel(identity.getServiceId());
        messageBus.subscribe(reregisterChannel, envelope -> {
            LOGGER.info("Registry requested targeted re-registration");
            sendRegistrationRequest();
            if (registered.get()) {
                sendHeartbeat();
            }
        });
        
        // Handle evacuation requests
        messageBus.subscribe(ChannelConstants.SERVER_EVACUATION_REQUEST, envelope -> {
            try {
                // Deserialize from JsonNode
                if (envelope.getPayload() instanceof com.fasterxml.jackson.databind.JsonNode) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode payload = (com.fasterxml.jackson.databind.JsonNode) envelope.getPayload();
                    
                    // Try to deserialize as ServerEvacuationRequest
                    try {
                        ServerEvacuationRequest request = mapper.treeToValue(payload, ServerEvacuationRequest.class);
                        if (request.getServerId() != null && request.getServerId().equals(identity.getServiceId())) {
                            handleEvacuationRequest(request);
                        }
                    } catch (Exception e1) {
                        // Fallback to Map handling for backwards compatibility
                        java.util.Map<String, Object> map = mapper.treeToValue(payload, java.util.Map.class);
                        String serverId = (String) map.get("serverId");
                        if (serverId != null && serverId.equals(identity.getServiceId())) {
                            ServerEvacuationRequest request = new ServerEvacuationRequest(
                                serverId,
                                (String) map.getOrDefault("reason", "Unknown")
                            );
                            handleEvacuationRequest(request);
                        }
                    }
                } else if (envelope.getPayload() instanceof java.util.Map) {
                    // Direct Map handling
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = (java.util.Map<String, Object>) envelope.getPayload();
                    String serverId = (String) map.get("serverId");
                    if (serverId != null && serverId.equals(identity.getServiceId())) {
                        ServerEvacuationRequest request = new ServerEvacuationRequest(
                            serverId,
                            (String) map.getOrDefault("reason", "Unknown")
                        );
                        handleEvacuationRequest(request);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to handle evacuation request", e);
            }
        });
    }
    
    /**
     * Handle registration response from registry.
     */
    private void handleRegistrationResponse(ServerRegistrationResponse response) {
        LOGGER.info("[DEBUG] Registration response handler invoked");
        LOGGER.info("[DEBUG] Response - success: " + response.isSuccess() +
                    ", assignedId: " + response.getAssignedServerId());
        
        if (response.isSuccess() && response.getAssignedServerId() != null) {
            LOGGER.info("[DEBUG] Entering successful registration block");
            LOGGER.info("Successfully registered with ID: " + response.getAssignedServerId());
            
            // Update identity with permanent ID
            LOGGER.info("[DEBUG] Updating service ID from " + identity.getServiceId() +
                        " to " + response.getAssignedServerId());
            identity.updateServiceId(response.getAssignedServerId());
            LOGGER.info("[DEBUG] Service ID updated successfully");
            
            LOGGER.info("[DEBUG] Setting registered flag to true (was: " + registered.get() + ")");
            registered.set(true);
            LOGGER.info("[DEBUG] Registered flag set to: " + registered.get());
            
            metadata.setStatus(ServiceStatus.AVAILABLE);
            LOGGER.info("[DEBUG] Status set to AVAILABLE");
            
            // Cancel retry task
            if (registrationRetryTask != null) {
                LOGGER.info("[DEBUG] Cancelling registration retry task");
                registrationRetryTask.cancel(false);
                LOGGER.info("[DEBUG] Registration retry task cancelled");
            } else {
                LOGGER.warning("[DEBUG] Registration retry task is null!");
            }
            
            // Invoke callback
            if (onRegistrationSuccess != null) {
                LOGGER.info("[DEBUG] Invoking registration success callback");
                onRegistrationSuccess.accept(response.getAssignedServerId());
            }
            
            // Send server announcement
            LOGGER.info("[DEBUG] Sending server announcement");
            sendServerAnnouncement();
            LOGGER.info("[DEBUG] Registration process completed successfully");
            
            // Complete the registration future if it hasn't been completed yet
            if (registrationFuture != null && !registrationFuture.isDone()) {
                registrationFuture.complete(null);
            }
        } else {
            LOGGER.warning("[DEBUG] Registration failed - success=" + response.isSuccess() +
                          ", assignedId=" + response.getAssignedServerId());
            LOGGER.warning("Registration failed: " + response.getMessage());
            
            if (onRegistrationFailure != null) {
                onRegistrationFailure.accept(response.getMessage());
            }
        }
    }
    
    /**
     * Start the heartbeat task.
     */
    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        LOGGER.info("Starting heartbeat for service: " + identity.getServiceId());
        
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                sendHeartbeat();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to send heartbeat", e);
            }
        }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send a heartbeat message.
     */
    private void sendHeartbeat() {
        // Allow custom heartbeat preparation
        if (onHeartbeat != null) {
            onHeartbeat.accept(metadata);
        }
        
        ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage(
            identity.getServiceId(),
            identity.getServiceType().getTypeName()
        );
        
        heartbeat.setPlayerCount(metadata.getPlayerCount());
        heartbeat.setMaxCapacity(metadata.getMaxCapacity());
        heartbeat.setTps(metadata.getTps());
        heartbeat.setUptime(identity.getUptime());
        heartbeat.setRole(identity.getRole());
        heartbeat.setStatus(metadata.getStatus().getStatusName());
        
        messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, heartbeat);
        
        LOGGER.fine(String.format("Sent heartbeat - Players: %d/%d, TPS: %.1f, Status: %s",
            metadata.getPlayerCount(), metadata.getMaxCapacity(), 
            metadata.getTps(), metadata.getStatus()));
    }
    
    /**
     * Send server announcement after registration.
     */
    private void sendServerAnnouncement() {
        ServerAnnouncementMessage announcement = new ServerAnnouncementMessage(
            identity.getServiceId(),
            identity.getServiceType().getTypeName(),
            identity.getRole(),
            identity.getRole(),
            metadata.getMaxCapacity(),
            identity.getAddress(),
            identity.getPort()
        );
        
        messageBus.broadcast(ChannelConstants.SERVER_ANNOUNCEMENT, announcement);
        LOGGER.info("Broadcast server announcement");
    }
    
    /**
     * Handle evacuation request.
     */
    private void handleEvacuationRequest(ServerEvacuationRequest request) {
        LOGGER.warning("Received evacuation request: " + request.getReason());
        metadata.setStatus(ServiceStatus.EVACUATING);
        
        // Send evacuation response
        ServerEvacuationResponse response = new ServerEvacuationResponse(
            identity.getServiceId(),
            true,
            0, // Will be updated by the actual service
            0,
            "Evacuation initiated"
        );
        
        messageBus.broadcast(ChannelConstants.SERVER_EVACUATION_RESPONSE, response);
    }
    
    /**
     * Shutdown the service lifecycle gracefully.
     */
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            if (running.compareAndSet(true, false)) {
                LOGGER.info("Shutting down service lifecycle for " + identity);
                
                metadata.setStatus(ServiceStatus.STOPPING);
                
                // Cancel tasks
                if (heartbeatTask != null) {
                    heartbeatTask.cancel(false);
                }
                if (registrationRetryTask != null) {
                    registrationRetryTask.cancel(false);
                }
                
                // Send shutdown notifications
                sendShutdownNotifications();
                
                // Invoke shutdown callback
                if (onShutdown != null) {
                    onShutdown.run();
                }
                
                // Shutdown scheduler
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                
                metadata.setStatus(ServiceStatus.STOPPED);
            }
        });
    }
    
    /**
     * Send shutdown notifications.
     */
    private void sendShutdownNotifications() {
        try {
            // Send removal notification
            ServerRemovalNotification removal = new ServerRemovalNotification(
                identity.getServiceId(),
                identity.getServiceType().getTypeName(),
                "SHUTDOWN"
            );
            messageBus.broadcast(ChannelConstants.REGISTRY_SERVER_REMOVED, removal);
            
            // Send shutdown heartbeat
            ServerHeartbeatMessage shutdownHeartbeat = new ServerHeartbeatMessage(
                identity.getServiceId(),
                identity.getServiceType().getTypeName()
            );
            shutdownHeartbeat.setStatus("SHUTDOWN");
            messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, shutdownHeartbeat);
            
            LOGGER.info("Sent shutdown notifications");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send shutdown notifications", e);
        }
    }
    
    // Callback setters
    
    public void onRegistrationSuccess(Consumer<String> callback) {
        this.onRegistrationSuccess = callback;
    }
    
    public void onRegistrationFailure(Consumer<String> callback) {
        this.onRegistrationFailure = callback;
    }
    
    public void onHeartbeat(Consumer<ServiceMetadata> callback) {
        this.onHeartbeat = callback;
    }
    
    public void onShutdown(Runnable callback) {
        this.onShutdown = callback;
    }
    
    // Getters
    
    public boolean isRegistered() {
        return registered.get();
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    public ServiceIdentity getIdentity() {
        return identity;
    }
    
    public ServiceMetadata getMetadata() {
        return metadata;
    }
}