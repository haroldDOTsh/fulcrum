package sh.harold.fulcrum.host.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Plugin(
        id = FulcrumVelocityPlugin.PLUGIN_ID,
        name = "FulcrumVelocityAgent",
        version = "0.1.0-SNAPSHOT",
        description = "Fulcrum Velocity host integration")
public final class FulcrumVelocityPlugin {
    static final String PLUGIN_ID = "fulcrum-velocity-agent";
    private static final Duration INITIAL_ROUTE_TIMEOUT = Duration.ofSeconds(90);

    private final ProxyServer proxyServer;
    private volatile VelocityPluginRuntimeConfiguration configuration;
    private volatile VelocityInitialRouteCoordinator initialRouteCoordinator;
    private volatile VelocityRouteExecutor routeExecutor;
    private volatile VelocityRouteBridgeServer routeBridgeServer;
    private volatile VelocityLoginAdmissionHandler loginAdmissionHandler;
    private final VelocityLoginSubjectRegistry loginSubjects = new VelocityLoginSubjectRegistry();

    @Inject
    public FulcrumVelocityPlugin(ProxyServer proxyServer) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        configuration = VelocityPluginRuntimeConfiguration.fromEnvironment(System.getenv());
        VelocityProxyGateway gateway = new VelocityProxyServerGateway(proxyServer, loginSubjects);
        VelocityInitialRouteCoordinator initialRoutes = new VelocityInitialRouteCoordinator(
                INITIAL_ROUTE_TIMEOUT,
                loginSubjects::username);
        initialRouteCoordinator = initialRoutes;
        routeExecutor = new VelocityRouteExecutor(
                configuration.securityContext(),
                configuration.proxyRouteCommandTopic(),
                new VelocityBackendRegistry(gateway),
                Clock.systemUTC(),
                initialRoutes);
        routeBridgeServer = new VelocityRouteBridgeServer(configuration.routeBridgeUrl(), routeExecutor);
        routeBridgeServer.start();
        loginAdmissionHandler = new VelocityLoginAdmissionHandler(
                new VelocityLoginGateBridgeClient(configuration.loginGateBridgeUrl()),
                configuration.loginGateScope(),
                Clock.systemUTC());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        VelocityRouteBridgeServer server = routeBridgeServer;
        if (server != null) {
            server.close();
        }
        VelocityInitialRouteCoordinator initialRoutes = initialRouteCoordinator;
        if (initialRoutes != null) {
            initialRoutes.close();
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        VelocityLoginAdmissionHandler handler = loginAdmissionHandler;
        SubjectId subjectId = new SubjectId(event.getPlayer().getUniqueId());
        loginSubjects.record(event.getPlayer().getUsername(), subjectId);
        if (handler == null) {
            loginSubjects.remove(event.getPlayer().getUsername());
            deny(event, VelocityLoginAdmissionHandler.BRIDGE_UNAVAILABLE_REASON);
            return;
        }
        VelocityLoginGateDecision decision = handler.evaluate(
                subjectId,
                event.getPlayer().getUsername());
        if (!decision.allowed()) {
            loginSubjects.remove(event.getPlayer().getUsername());
            deny(event, decision.denialReason().orElse(VelocityLoginAdmissionHandler.BRIDGE_UNAVAILABLE_REASON));
            return;
        }
        loginSubjects.record(event.getPlayer().getUsername(), decision.subjectId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        loginSubjects.remove(event.getPlayer().getUsername());
    }

    @Subscribe
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        VelocityInitialRouteCoordinator coordinator = initialRouteCoordinator;
        if (coordinator == null) {
                return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
        }
        SubjectId subjectId = loginSubjects.consume(
                event.getPlayer().getUsername(),
                new SubjectId(event.getPlayer().getUniqueId()));
        CompletableFuture<Void> route = coordinator.await(subjectId, event.getPlayer().getUsername())
                .thenAccept(selection -> selection.ifPresent(value -> applyInitialRoute(event, value)))
                .toCompletableFuture();
        return EventTask.resumeWhenComplete(route);
    }

    VelocityPluginRuntimeConfiguration configuration() {
        return configuration;
    }

    VelocityRouteExecutor routeExecutor() {
        return routeExecutor;
    }

    VelocityRouteBridgeServer routeBridgeServer() {
        return routeBridgeServer;
    }

    VelocityLoginAdmissionHandler loginAdmissionHandler() {
        return loginAdmissionHandler;
    }

    private void applyInitialRoute(PlayerChooseInitialServerEvent event, VelocityInitialRouteSelection selection) {
        Optional<RegisteredServer> server = proxyServer.getServer(selection.backendName());
        if (server.isPresent()) {
            event.setInitialServer(server.orElseThrow());
            selection.acknowledge(true);
        } else {
            selection.acknowledge(false);
        }
    }

    private static void deny(LoginEvent event, String reason) {
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(reason)));
    }
}
