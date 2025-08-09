package sh.harold.fulcrum.api.lifecycle;

import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central registry for all servers in the ecosystem.
 */
public interface ServerRegistry {
    
    /**
     * Registers a new server or reclaims an existing ID after crash.
     */
    CompletableFuture<RegistrationResult> register(ServerRegistration registration, UUID instanceUuid);
    
    /**
     * Updates server status.
     */
    CompletableFuture<Boolean> updateStatus(String serverId, ServerStatus status);
    
    /**
     * Sends a heartbeat for a server.
     */
    CompletableFuture<Boolean> heartbeat(ServerHeartbeat heartbeat);
    
    /**
     * Gets server metadata by ID.
     */
    CompletableFuture<Optional<ServerMetadata>> getServer(String serverId);
    
    /**
     * Gets all servers of a specific family.
     */
    CompletableFuture<Collection<ServerMetadata>> getServersByFamily(String family);
    
    /**
     * Gets all servers of a specific type.
     */
    CompletableFuture<Collection<ServerMetadata>> getServersByType(ServerType type);
    
    /**
     * Gets all servers with a specific status.
     */
    CompletableFuture<Collection<ServerMetadata>> getServersByStatus(ServerStatus status);
    
    /**
     * Gets all registered servers.
     */
    CompletableFuture<Collection<ServerMetadata>> getAllServers();
    
    /**
     * Unregisters a server.
     */
    CompletableFuture<Boolean> unregister(String serverId);
    
    /**
     * Checks for crashed servers and marks them as offline.
     */
    CompletableFuture<Collection<String>> checkCrashedServers(int timeoutSeconds);
    
    /**
     * Checks if a server ID is reserved (crashed but within grace period).
     */
    CompletableFuture<Boolean> isReserved(String serverId, UUID instanceUuid);
    
    /**
     * Gets the best available server for a player to join.
     * Considers capacity, TPS, and server health.
     */
    CompletableFuture<Optional<ServerMetadata>> getBestServer(String family);
}