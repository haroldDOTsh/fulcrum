package sh.harold.fulcrum.fundamentals.lifecycle;

import org.bukkit.Bukkit;
import sh.harold.fulcrum.api.lifecycle.*;
import sh.harold.fulcrum.api.lifecycle.registration.RegistrationResult;
import sh.harold.fulcrum.api.lifecycle.registration.ServerRegistration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerLifecycleBootstrapImpl implements ServerLifecycleBootstrap {
    
    private final JavaPlugin plugin;
    private final ServerRegistry registry;
    private String assignedServerId;
    private UUID instanceUuid;
    
    public ServerLifecycleBootstrapImpl(JavaPlugin plugin, ServerRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.instanceUuid = UUID.randomUUID();
    }
    
    @Override
    public CompletableFuture<RegistrationResult> bootstrapRegister(
            ServerRegistration registration,
            UUID instanceUuid) {
        this.instanceUuid = instanceUuid;
        
        // Register with the registry
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
    
    /**
     * Helper method to create a registration from config
     */
    public ServerRegistration createRegistrationFromConfig() {
        ConfigurationSection lifecycleConfig = plugin.getConfig().getConfigurationSection("server-lifecycle");
        
        // Get server type from config
        String typeStr = lifecycleConfig != null ? lifecycleConfig.getString("type", "game") : "game";
        ServerType serverType = parseServerType(typeStr);
        
        // Get family from config
        String family = lifecycleConfig != null ? lifecycleConfig.getString("family", "default") : "default";
        
        // Get server address and port
        String address = Bukkit.getServer().getIp();
        if (address == null || address.isEmpty()) {
            address = "localhost";
        }
        int port = Bukkit.getServer().getPort();
        
        int maxPlayers = Bukkit.getMaxPlayers();
        
        // Create registration based on type
        if (serverType == ServerType.PROXY) {
            return ServerRegistration.proxy(family, address, port);
        } else {
            // For game servers, use max players as soft cap and add 10 for hard cap
            return ServerRegistration.game(
                family,
                address,
                port,
                maxPlayers,
                maxPlayers + 10
            );
        }
    }
    
    private ServerType parseServerType(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) {
            return ServerType.GAME;
        }
        
        switch (typeStr.toLowerCase()) {
            case "proxy":
                return ServerType.PROXY;
            case "game":
            default:
                return ServerType.GAME;
        }
    }
}