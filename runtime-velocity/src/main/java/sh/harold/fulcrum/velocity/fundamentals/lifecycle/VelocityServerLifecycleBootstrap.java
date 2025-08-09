package sh.harold.fulcrum.velocity.fundamentals.lifecycle;

import sh.harold.fulcrum.api.lifecycle.ServerLifecycleBootstrap;
import sh.harold.fulcrum.api.lifecycle.ServerRegistry;
import sh.harold.fulcrum.api.lifecycle.ServerStatus;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity implementation of ServerLifecycleBootstrap.
 * Handles the server lifecycle for Velocity proxy servers.
 */
public class VelocityServerLifecycleBootstrap implements ServerLifecycleBootstrap {
    
    private final ServerRegistry registry;
    private final UUID instanceUuid;
    private String assignedServerId;
    
    public VelocityServerLifecycleBootstrap(ServerRegistry registry, UUID instanceUuid) {
        this.registry = registry;
        this.instanceUuid = instanceUuid;
    }
    
    @Override
    public CompletableFuture<RegistrationResult> bootstrapRegister(
            ServerRegistration registration,
            UUID instanceUuid) {
        return registry.register(registration, instanceUuid)
            .thenApply(result -> {
                if (result.success()) {
                    this.assignedServerId = result.serverId();
                }
                return result;
            });
    }
    
    @Override
    public CompletableFuture<Boolean> markReady(String serverId) {
        return registry.updateStatus(serverId, ServerStatus.READY);
    }
    
    @Override
    public CompletableFuture<Boolean> beginShutdown(String serverId) {
        return registry.updateStatus(serverId, ServerStatus.STOPPING);
    }
    
    @Override
    public CompletableFuture<Boolean> completeShutdown(String serverId) {
        return registry.updateStatus(serverId, ServerStatus.OFFLINE)
            .thenCompose(result -> registry.unregister(serverId));
    }
    
    @Override
    public String getAssignedServerId() {
        return assignedServerId;
    }
    
    @Override
    public UUID getInstanceUuid() {
        return instanceUuid;
    }
}