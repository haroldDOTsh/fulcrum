package sh.harold.fulcrum.messagebus.adapter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Adapter interface that runtime modules must implement to provide
 * platform-specific services to the message bus.
 * This adapter isolates the message bus from platform-specific details.
 */
public interface MessageBusAdapter {
    
    /**
     * Get the connection configuration for the message bus.
     * @return Connection configuration
     */
    MessageBusConnectionConfig getConnectionConfig();
    
    /**
     * Get the unique identifier for this server/proxy instance.
     * @return Server identifier
     */
    String getServerIdentifier();
    
    /**
     * Get a scheduler for async operations.
     * @return Scheduler service
     */
    ScheduledExecutorService getScheduler();
    
    /**
     * Get a logger for the message bus.
     * @return Logger instance
     */
    Logger getLogger();
}