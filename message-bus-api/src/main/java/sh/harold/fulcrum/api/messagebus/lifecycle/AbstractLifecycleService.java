package sh.harold.fulcrum.api.messagebus.lifecycle;

import sh.harold.fulcrum.api.messagebus.MessageBus;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Abstract base class for all services that need lifecycle management.
 * Provides common functionality for registration, heartbeats, and shutdown.
 */
public abstract class AbstractLifecycleService {
    
    private static final Logger LOGGER = Logger.getLogger(AbstractLifecycleService.class.getName());
    
    protected final MessageBus messageBus;
    protected final ServiceIdentity identity;
    protected final ServiceMetadata metadata;
    protected final ServiceLifecycleManager lifecycleManager;
    
    /**
     * Create a new lifecycle service.
     * 
     * @param messageBus The message bus for communication
     * @param serviceType The type of service
     * @param role The role of the service
     * @param address The service address
     * @param port The service port
     * @param maxCapacity The maximum capacity
     */
    protected AbstractLifecycleService(MessageBus messageBus, ServiceType serviceType, 
                                      String role, String address, int port, int maxCapacity) {
        this.messageBus = messageBus;
        
        // Generate temp ID
        String tempId = "temp-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Create identity
        this.identity = new ServiceIdentity(tempId, serviceType, role, address, port);
        
        // Create metadata
        this.metadata = new ServiceMetadata(maxCapacity);
        
        // Create lifecycle manager
        this.lifecycleManager = new ServiceLifecycleManager(messageBus, identity, metadata);
        
        // Setup callbacks
        setupLifecycleCallbacks();
    }
    
    /**
     * Setup lifecycle callbacks.
     */
    protected void setupLifecycleCallbacks() {
        // Default implementation - subclasses can override
        lifecycleManager.onRegistrationSuccess(this::onRegistrationSuccess);
        lifecycleManager.onRegistrationFailure(this::onRegistrationFailure);
        lifecycleManager.onHeartbeat(this::onHeartbeat);
        lifecycleManager.onShutdown(this::onShutdown);
    }
    
    /**
     * Start the service.
     */
    public CompletableFuture<Void> start() {
        LOGGER.info("Starting " + identity.getServiceType() + " service: " + identity.getTempId());
        
        // Initialize service-specific components
        CompletableFuture<Void> initFuture = initialize();
        
        // Start lifecycle management after initialization
        return initFuture.thenCompose(v -> lifecycleManager.start())
                        .thenRun(() -> {
                            LOGGER.info("Service started successfully: " + identity.getServiceId());
                            onStarted();
                        });
    }
    
    /**
     * Stop the service.
     */
    public CompletableFuture<Void> stop() {
        LOGGER.info("Stopping service: " + identity.getServiceId());
        
        // Shutdown lifecycle first
        return lifecycleManager.shutdown()
                              .thenCompose(v -> cleanup())
                              .thenRun(() -> {
                                  LOGGER.info("Service stopped: " + identity.getServiceId());
                                  onStopped();
                              });
    }
    
    /**
     * Initialize service-specific components.
     * Subclasses should override this to perform initialization.
     */
    protected CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Cleanup service-specific components.
     * Subclasses should override this to perform cleanup.
     */
    protected CompletableFuture<Void> cleanup() {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Called when registration succeeds.
     * 
     * @param permanentId The permanent ID assigned by the registry
     */
    protected void onRegistrationSuccess(String permanentId) {
        LOGGER.info("Registration successful. Permanent ID: " + permanentId);
    }
    
    /**
     * Called when registration fails.
     * 
     * @param reason The reason for failure
     */
    protected void onRegistrationFailure(String reason) {
        LOGGER.severe("Registration failed: " + reason);
    }
    
    /**
     * Called before each heartbeat is sent.
     * Subclasses can override to update metrics.
     * 
     * @param metadata The service metadata to update
     */
    protected void onHeartbeat(ServiceMetadata metadata) {
        // Subclasses should update metrics here
        // e.g., metadata.setPlayerCount(getCurrentPlayerCount());
    }
    
    /**
     * Called during shutdown.
     */
    protected void onShutdown() {
        // Subclasses can override for custom shutdown logic
    }
    
    /**
     * Called after the service has started successfully.
     */
    protected void onStarted() {
        // Subclasses can override
    }
    
    /**
     * Called after the service has stopped.
     */
    protected void onStopped() {
        // Subclasses can override
    }
    
    /**
     * Update service status.
     * 
     * @param status The new status
     */
    public void updateStatus(ServiceStatus status) {
        metadata.setStatus(status);
    }
    
    /**
     * Update service metrics.
     * 
     * @param playerCount Current player count
     * @param tps Current TPS
     */
    public void updateMetrics(int playerCount, double tps) {
        metadata.updateMetrics(playerCount, tps);
    }
    
    /**
     * Set a custom property.
     * 
     * @param key Property key
     * @param value Property value
     */
    public void setProperty(String key, Object value) {
        metadata.setProperty(key, value);
    }
    
    /**
     * Get a custom property.
     * 
     * @param key Property key
     * @param type Property type
     * @return The property value or null
     */
    public <T> T getProperty(String key, Class<T> type) {
        return metadata.getProperty(key, type);
    }
    
    // Getters
    
    public ServiceIdentity getIdentity() {
        return identity;
    }
    
    public ServiceMetadata getMetadata() {
        return metadata;
    }
    
    public boolean isRegistered() {
        return lifecycleManager.isRegistered();
    }
    
    public boolean isRunning() {
        return lifecycleManager.isRunning();
    }
    
    public String getServiceId() {
        return identity.getServiceId();
    }
    
    public ServiceType getServiceType() {
        return identity.getServiceType();
    }
    
    public String getRole() {
        return identity.getRole();
    }
    
    public ServiceStatus getStatus() {
        return metadata.getStatus();
    }
}