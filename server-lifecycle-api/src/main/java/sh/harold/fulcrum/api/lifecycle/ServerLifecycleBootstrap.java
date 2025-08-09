package sh.harold.fulcrum.api.lifecycle;

import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bootstrap interface for early server registration.
 * This is called before the server is fully initialized.
 */
public interface ServerLifecycleBootstrap {
    
    /**
     * Performs bootstrap registration of the server.
     * Called early in server startup, before most systems are initialized.
     */
    CompletableFuture<RegistrationResult> bootstrapRegister(
        ServerRegistration registration,
        UUID instanceUuid
    );
    
    /**
     * Transitions the server to READY status.
     * Called when the server is fully initialized and ready to accept players.
     */
    CompletableFuture<Boolean> markReady(String serverId);
    
    /**
     * Begins the shutdown process.
     * Called when the server is shutting down.
     */
    CompletableFuture<Boolean> beginShutdown(String serverId);
    
    /**
     * Completes the shutdown process.
     * Called after all shutdown tasks are complete.
     */
    CompletableFuture<Boolean> completeShutdown(String serverId);
    
    /**
     * Gets the assigned server ID from bootstrap registration.
     */
    String getAssignedServerId();
    
    /**
     * Gets the server's instance UUID.
     */
    UUID getInstanceUuid();
}