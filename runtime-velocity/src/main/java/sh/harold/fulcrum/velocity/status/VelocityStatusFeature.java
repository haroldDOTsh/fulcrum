package sh.harold.fulcrum.velocity.status;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.status.PresenceStatus;
import sh.harold.fulcrum.api.status.StatusService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Proxy-side feature that exposes the shared status service and keeps presence in sync.
 */
public final class VelocityStatusFeature implements VelocityFeature {

    private ProxyServer proxyServer;
    private FulcrumVelocityPlugin plugin;
    private StatusService statusService;
    private StatusListener listener;

    @Override
    public String getName() {
        return "StatusService";
    }

    @Override
    public int getPriority() {
        return 28; // After session feature (25) and before player data (30)
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        VelocityPlayerSessionService sessionService = serviceLocator.getRequiredService(VelocityPlayerSessionService.class);
        DataAPI dataAPI = serviceLocator.getService(DataAPI.class).orElse(null);
        Executor executor = dataAPI != null ? dataAPI.executor() : ForkJoinPool.commonPool();

        this.statusService = new VelocityStatusService(sessionService, dataAPI, executor, logger);
        serviceLocator.register(StatusService.class, statusService);

        this.listener = new StatusListener(statusService);
        proxyServer.getEventManager().register(plugin, listener);
    }

    @Override
    public void shutdown() {
        if (proxyServer != null && plugin != null && listener != null) {
            proxyServer.getEventManager().unregisterListener(plugin, listener);
        }
        proxyServer = null;
        plugin = null;
        statusService = null;
        listener = null;
    }

    private static final class StatusListener {
        private final StatusService statusService;

        private StatusListener(StatusService statusService) {
            this.statusService = statusService;
        }

        @Subscribe
        public void onLogin(PostLoginEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            statusService.updateStatus(playerId, PresenceStatus.ONLINE, null);
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            statusService.updateStatus(playerId, PresenceStatus.OFFLINE, null);
        }
    }
}
