package sh.harold.fulcrum.api.messagebus.adapter;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Adapter interface that runtime modules implement to provide
 * platform-specific functionality to the message bus.
 * <p>
 * This allows the message-bus-api to remain independent of
 * Minecraft or Velocity specific APIs and remain stateless.
 */
public interface MessageBusAdapter {

    /**
     * Gets the unique server identifier for this instance.
     * This should be consistent across restarts if possible.
     *
     * @return the server identifier
     */
    String getServerId();

    /**
     * Gets an executor for running asynchronous tasks.
     * This could be a scheduler service, thread pool, or
     * platform-specific async mechanism.
     *
     * @return an executor for async operations
     */
    Executor getAsyncExecutor();

    /**
     * Gets the logger instance for the message bus.
     *
     * @return the logger
     */
    Logger getLogger();

    /**
     * Gets the configuration for the message bus connection.
     *
     * @return the connection configuration
     */
    MessageBusConnectionConfig getConnectionConfig();

    /**
     * Checks if the adapter is still running.
     * This is used to gracefully stop processing when shutting down.
     *
     * @return true if running, false if shutting down
     */
    boolean isRunning();

    /**
     * Called when the message bus is fully initialized.
     * Can be used for platform-specific initialization.
     */
    default void onMessageBusReady() {
        // Default no-op implementation
    }

    /**
     * Called when the message bus is shutting down.
     * Can be used for platform-specific cleanup.
     */
    default void onMessageBusShutdown() {
        // Default no-op implementation
    }
}