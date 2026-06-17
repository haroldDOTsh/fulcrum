package sh.harold.fulcrum.host.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class VelocityProxyServerGateway implements VelocityProxyGateway {
    private final ProxyServer proxyServer;

    public VelocityProxyServerGateway(ProxyServer proxyServer) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
    }

    @Override
    public void registerBackend(String backendName, InetSocketAddress address) {
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(address, "address");
        proxyServer.registerServer(new ServerInfo(backendName, address));
    }

    @Override
    public CompletionStage<Boolean> transfer(SubjectId subjectId, String backendName) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(backendName, "backendName");
        Optional<Player> player = proxyServer.getPlayer(subjectId.value());
        Optional<RegisteredServer> server = proxyServer.getServer(backendName);
        if (player.isEmpty() || server.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return player.orElseThrow()
                .createConnectionRequest(server.orElseThrow())
                .connect()
                .thenApply(result -> result.isSuccessful());
    }
}
