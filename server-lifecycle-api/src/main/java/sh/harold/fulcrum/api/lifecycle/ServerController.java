package sh.harold.fulcrum.api.lifecycle;

import sh.harold.fulcrum.api.lifecycle.control.RestartRequest;
import sh.harold.fulcrum.api.lifecycle.control.ShutdownRequest;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for remote server control operations.
 */
public interface ServerController {
    
    /**
     * Requests a server shutdown.
     */
    CompletableFuture<Boolean> shutdown(ShutdownRequest request);
    
    /**
     * Requests a server restart.
     */
    CompletableFuture<Boolean> restart(RestartRequest request);
    
    /**
     * Cancels a pending shutdown or restart.
     */
    CompletableFuture<Boolean> cancelShutdown(String serverId);
    
    /**
     * Sends a message to all players on a server.
     */
    CompletableFuture<Boolean> broadcast(String serverId, String message);
    
    /**
     * Checks if a server has a pending shutdown.
     */
    CompletableFuture<Boolean> hasPendingShutdown(String serverId);
    
    /**
     * Gets the remaining time until shutdown (in seconds).
     * Returns -1 if no shutdown is pending.
     */
    CompletableFuture<Integer> getShutdownCountdown(String serverId);
    
    /**
     * Forces an immediate emergency shutdown.
     * Should only be used in critical situations.
     */
    CompletableFuture<Boolean> forceShutdown(String serverId);
}