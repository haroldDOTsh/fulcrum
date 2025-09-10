package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velocity-specific implementation of MessageBusAdapter.
 * Provides platform-specific functionality to the message bus.
 */
public class VelocityMessageBusAdapter implements MessageBusAdapter {
    private final ProxyServer proxy;
    private final FulcrumVelocityPlugin plugin;
    private final Logger logger;
    private final ServiceLocator serviceLocator;
    private final MessageBusConnectionConfig config;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private String proxyId;
    
    public VelocityMessageBusAdapter(ProxyServer proxy, FulcrumVelocityPlugin plugin, 
                                     ServiceLocator serviceLocator,
                                     MessageBusConnectionConfig config,
                                     Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.serviceLocator = serviceLocator;
        this.config = config;
        
        // Generate temporary proxy ID (will be updated by VelocityServerLifecycleFeature)
        this.proxyId = "temp-proxy-" + java.util.UUID.randomUUID();
    }
    
    @Override
    public String getServerId() {
        return proxyId;
    }
    
    /**
     * Update the proxy ID when Registry Service assigns a permanent one
     */
    public void updateProxyId(String newProxyId) {
        String oldId = this.proxyId;
        this.proxyId = newProxyId;
        logger.info("[REGISTRATION_FLOW] VelocityMessageBusAdapter ID updated from {} to {}", oldId, newProxyId);
        logger.info("[REGISTRATION_FLOW] All future messages will use sender ID: {}", this.proxyId);
    }
    
    @Override
    public Executor getAsyncExecutor() {
        // Use Velocity's scheduler as executor
        return task -> proxy.getScheduler()
            .buildTask(plugin, task)
            .schedule();
    }
    
    @Override
    public java.util.logging.Logger getLogger() {
        // Convert SLF4J logger to java.util.logging.Logger
        return java.util.logging.Logger.getLogger(VelocityMessageBusAdapter.class.getName());
    }
    
    @Override
    public MessageBusConnectionConfig getConnectionConfig() {
        return config;
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public void onMessageBusReady() {
        logger.info("Message bus is ready and operational");
    }
    
    @Override
    public void onMessageBusShutdown() {
        running.set(false);
        logger.info("Message bus shutting down");
    }
    
    /**
     * Shutdown the adapter.
     */
    public void shutdown() {
        running.set(false);
    }
}