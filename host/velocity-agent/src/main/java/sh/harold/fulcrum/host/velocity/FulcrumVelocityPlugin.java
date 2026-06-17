package sh.harold.fulcrum.host.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Clock;
import java.util.Objects;

@Plugin(
        id = FulcrumVelocityPlugin.PLUGIN_ID,
        name = "FulcrumVelocityAgent",
        version = "0.1.0-SNAPSHOT",
        description = "Fulcrum Velocity host integration")
public final class FulcrumVelocityPlugin {
    static final String PLUGIN_ID = "fulcrum-velocity-agent";

    private final ProxyServer proxyServer;
    private volatile VelocityPluginRuntimeConfiguration configuration;
    private volatile VelocityRouteExecutor routeExecutor;
    private volatile VelocityRouteBridgeServer routeBridgeServer;
    private volatile VelocityLoginAdmissionHandler loginAdmissionHandler;

    @Inject
    public FulcrumVelocityPlugin(ProxyServer proxyServer) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        configuration = VelocityPluginRuntimeConfiguration.fromEnvironment(System.getenv());
        VelocityProxyGateway gateway = new VelocityProxyServerGateway(proxyServer);
        routeExecutor = new VelocityRouteExecutor(
                configuration.securityContext(),
                configuration.proxyRouteCommandTopic(),
                new VelocityBackendRegistry(gateway),
                Clock.systemUTC());
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
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        VelocityLoginAdmissionHandler handler = loginAdmissionHandler;
        if (handler == null) {
            deny(event, VelocityLoginAdmissionHandler.BRIDGE_UNAVAILABLE_REASON);
            return;
        }
        VelocityLoginGateDecision decision = handler.evaluate(
                new SubjectId(event.getPlayer().getUniqueId()),
                event.getPlayer().getUsername());
        if (!decision.allowed()) {
            deny(event, decision.denialReason().orElse(VelocityLoginAdmissionHandler.BRIDGE_UNAVAILABLE_REASON));
        }
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

    private static void deny(LoginEvent event, String reason) {
        event.setResult(ResultedEvent.ComponentResult.denied(Component.text(reason)));
    }
}
