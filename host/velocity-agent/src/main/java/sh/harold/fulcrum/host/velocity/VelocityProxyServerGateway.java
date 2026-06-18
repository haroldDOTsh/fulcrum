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
import java.util.function.Function;

public final class VelocityProxyServerGateway implements VelocityProxyGateway {
    private final ProxyServer proxyServer;
    private final Function<SubjectId, Optional<String>> usernameLookup;

    public VelocityProxyServerGateway(ProxyServer proxyServer) {
        this(proxyServer, ignored -> Optional.empty());
    }

    VelocityProxyServerGateway(
            ProxyServer proxyServer,
            VelocityLoginSubjectRegistry loginSubjects) {
        this(proxyServer, loginSubjects::username);
    }

    private VelocityProxyServerGateway(
            ProxyServer proxyServer,
            Function<SubjectId, Optional<String>> usernameLookup) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.usernameLookup = Objects.requireNonNull(usernameLookup, "usernameLookup");
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
        if (player.isEmpty()) {
            player = usernameLookup.apply(subjectId).flatMap(proxyServer::getPlayer);
        }
        Optional<RegisteredServer> server = proxyServer.getServer(backendName);
        if (player.isEmpty() || server.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        Player routedPlayer = player.orElseThrow();
        if (routedPlayer.getCurrentServer().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return routedPlayer
                .createConnectionRequest(server.orElseThrow())
                .connect()
                .thenApply(result -> result.isSuccessful());
    }
}
