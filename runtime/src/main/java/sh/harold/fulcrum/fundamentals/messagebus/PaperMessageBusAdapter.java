package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.module.ServiceLocator;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Paper/Bukkit specific implementation of MessageBusAdapter.
 * Provides platform-specific functionality to the message bus.
 */
public class PaperMessageBusAdapter implements MessageBusAdapter {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final MessageBusConnectionConfig config;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public PaperMessageBusAdapter(JavaPlugin plugin, MessageBusConnectionConfig config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
    }

    @Override
    public String getServerId() {
        // Lazy initialization - get ServiceLocator when needed
        ServiceLocator serviceLocator = ServiceLocatorImpl.getInstance();

        // If ServiceLocator is available, try to get server identifier
        if (serviceLocator != null) {
            return serviceLocator.findService(ServerIdentifier.class)
                    .map(ServerIdentifier::getServerId)
                    .orElse("paper-" + plugin.getServer().getPort());
        }

        // Fallback if ServiceLocator not yet initialized
        return "paper-" + plugin.getServer().getPort();
    }

    @Override
    public Executor getAsyncExecutor() {
        // Use Bukkit's async scheduler as executor
        return task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public MessageBusConnectionConfig getConnectionConfig() {
        return config;
    }

    @Override
    public boolean isRunning() {
        return running.get() && plugin.isEnabled();
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