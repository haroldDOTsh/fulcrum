package sh.harold.fulcrum.registry.adapter;

import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Message bus adapter for the Registry Service.
 * This is a standalone Java service (non-Minecraft) adapter implementation.
 */
public class RegistryMessageBusAdapter implements MessageBusAdapter {
    
    private final String serverId;
    private final MessageBusConnectionConfig connectionConfig;
    private final ScheduledExecutorService executorService;
    private final Logger logger;
    private volatile boolean running = true;
    
    public RegistryMessageBusAdapter(MessageBusConnectionConfig connectionConfig) {
        this(connectionConfig, null);
    }
    
    public RegistryMessageBusAdapter(MessageBusConnectionConfig connectionConfig, ScheduledExecutorService executorService) {
        this.serverId = "registry-service";
        this.connectionConfig = connectionConfig;
        this.executorService = executorService != null ? executorService : Executors.newScheduledThreadPool(4);
        this.logger = Logger.getLogger("RegistryService");
    }
    
    @Override
    public String getServerId() {
        return serverId;
    }
    
    @Override
    public Executor getAsyncExecutor() {
        return executorService;
    }
    
    @Override
    public Logger getLogger() {
        return logger;
    }
    
    @Override
    public MessageBusConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public void onMessageBusReady() {
        logger.info("Message bus initialized for Registry Service with ID: " + serverId);
    }
    
    @Override
    public void onMessageBusShutdown() {
        logger.info("Message bus shutting down for Registry Service");
        running = false;
    }
    
    /**
     * Shutdown the adapter and its resources.
     */
    public void shutdown() {
        running = false;
        if (!executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}