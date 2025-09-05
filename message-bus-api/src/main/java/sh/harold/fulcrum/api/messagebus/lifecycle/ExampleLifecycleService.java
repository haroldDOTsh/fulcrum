package sh.harold.fulcrum.api.messagebus.lifecycle;

import sh.harold.fulcrum.api.messagebus.MessageBus;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Example implementation of a service using the lifecycle management system.
 * This demonstrates how services can extend AbstractLifecycleService to gain
 * automatic registration, heartbeat, and shutdown capabilities.
 */
public class ExampleLifecycleService extends AbstractLifecycleService {
    
    private static final Logger LOGGER = Logger.getLogger(ExampleLifecycleService.class.getName());
    
    // Example service-specific state
    private int processedRequests = 0;
    private long lastRequestTime = 0;
    
    /**
     * Create a new example service.
     * 
     * @param messageBus The message bus for communication
     * @param address The service address
     * @param port The service port
     */
    public ExampleLifecycleService(MessageBus messageBus, String address, int port) {
        // Initialize with SERVER type, "example" role, and 100 max capacity
        super(messageBus, ServiceType.SERVER, "example", address, port, 100);
    }
    
    @Override
    protected CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Initializing example service components...");
            
            // Example: Initialize database connections, load configurations, etc.
            // This happens before registration
            
            // Set custom properties
            setProperty("version", "1.0.0");
            setProperty("environment", "production");
            
            LOGGER.info("Example service initialized");
        });
    }
    
    @Override
    protected CompletableFuture<Void> cleanup() {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Cleaning up example service resources...");
            
            // Example: Close connections, save state, etc.
            
            LOGGER.info("Example service cleanup completed");
        });
    }
    
    @Override
    protected void onRegistrationSuccess(String permanentId) {
        super.onRegistrationSuccess(permanentId);
        LOGGER.info("Example service registered with permanent ID: " + permanentId);
        
        // Start service-specific operations
        startProcessing();
    }
    
    @Override
    protected void onRegistrationFailure(String reason) {
        super.onRegistrationFailure(reason);
        LOGGER.severe("Example service registration failed: " + reason);
        
        // Could implement fallback logic here
    }
    
    @Override
    protected void onHeartbeat(ServiceMetadata metadata) {
        // Update metrics before heartbeat is sent
        
        // Example: Simulate player count based on processed requests
        int simulatedPlayerCount = Math.min(processedRequests % 50, metadata.getMaxCapacity());
        metadata.setPlayerCount(simulatedPlayerCount);
        
        // Example: Simulate TPS based on load
        double simulatedTps = 20.0 - (simulatedPlayerCount * 0.1);
        metadata.setTps(simulatedTps);
        
        // Update status based on capacity
        if (metadata.isAtHardCap()) {
            metadata.setStatus(ServiceStatus.FULL);
        } else if (metadata.isAtSoftCap()) {
            metadata.setStatus(ServiceStatus.AVAILABLE); // But near capacity
        } else {
            metadata.setStatus(ServiceStatus.AVAILABLE);
        }
        
        LOGGER.fine("Heartbeat metrics updated - Players: " + simulatedPlayerCount + 
                   ", TPS: " + simulatedTps);
    }
    
    @Override
    protected void onShutdown() {
        LOGGER.info("Example service is shutting down gracefully");
        
        // Perform any final operations
        LOGGER.info("Processed " + processedRequests + " total requests");
    }
    
    @Override
    protected void onStarted() {
        LOGGER.info("Example service is now fully operational");
    }
    
    @Override
    protected void onStopped() {
        LOGGER.info("Example service has been stopped");
    }
    
    /**
     * Start processing requests (example service logic).
     */
    private void startProcessing() {
        // This would be your actual service logic
        // For example, starting a server, listening to messages, etc.
        
        LOGGER.info("Example service started processing requests");
        
        // Simulate request processing
        new Thread(() -> {
            while (isRunning()) {
                try {
                    Thread.sleep(1000); // Simulate work
                    processRequest();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }
    
    /**
     * Process a simulated request.
     */
    private void processRequest() {
        processedRequests++;
        lastRequestTime = System.currentTimeMillis();
        
        if (processedRequests % 10 == 0) {
            LOGGER.info("Processed " + processedRequests + " requests");
        }
    }
    
    /**
     * Main method for testing the lifecycle service.
     */
    public static void main(String[] args) {
        // This is just an example of how to use the service
        
        // 1. Create a message bus (would be injected in real usage)
        // MessageBus messageBus = new InMemoryMessageBus();
        
        // 2. Create and start the service
        // ExampleLifecycleService service = new ExampleLifecycleService(messageBus, "localhost", 8080);
        // service.start().thenRun(() -> {
        //     System.out.println("Service started successfully!");
        // });
        
        // 3. Run for some time...
        
        // 4. Stop the service gracefully
        // service.stop().thenRun(() -> {
        //     System.out.println("Service stopped successfully!");
        // });
    }
}