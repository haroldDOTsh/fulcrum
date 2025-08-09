package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;
import sh.harold.fulcrum.api.messagebus.MessageBus;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VelocityPlayerLocator extends PlayerLocator {
    
    private final ProxyServer server;
    
    public VelocityPlayerLocator(ProxyServer server, MessageBus messageBus) {
        super(messageBus);
        this.server = server;
    }
    
    @Override
    public CompletableFuture<Boolean> isPlayerOnline(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            return server.getPlayer(playerId).isPresent();
        });
    }
    
    public Optional<Player> getPlayer(UUID playerId) {
        return server.getPlayer(playerId);
    }
    
    public Optional<RegisteredServer> getServer(String serverName) {
        return server.getServer(serverName);
    }
}