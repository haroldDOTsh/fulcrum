package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.common.cache.PlayerCache;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class VelocityPlayerDataFeature implements VelocityFeature {
    private Logger logger;
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private VelocityPlayerSessionService sessionService;
    private DataAPI dataAPI;
    private ServiceLocator serviceLocator;
    private PlayerSettingsService playerSettingsService;
    private PlayerCache playerCache;
    private ExecutorService playerCacheExecutor;

    @Override
    public String getName() {
        return "PlayerData";
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;

        // Get required services
        this.proxy = serviceLocator.getService(ProxyServer.class).orElseThrow(
                () -> new RuntimeException("ProxyServer not available"));
        this.sessionService = serviceLocator.getService(VelocityPlayerSessionService.class).orElseThrow(
                () -> new RuntimeException("VelocityPlayerSessionService not available"));
        this.plugin = serviceLocator.getService(FulcrumVelocityPlugin.class).orElseThrow(
                () -> new RuntimeException("FulcrumVelocityPlugin not available"));
        this.dataAPI = serviceLocator.getService(DataAPI.class).orElseThrow(
                () -> new RuntimeException("DataAPI not available"));

        this.playerCacheExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                new NamedThreadFactory("velocity-player-cache")
        );
        this.playerCache = new VelocityPlayerCache(dataAPI, sessionService, playerCacheExecutor);
        this.playerSettingsService = new VelocityPlayerSettingsService(playerCache, sessionService);
        serviceLocator.register(PlayerCache.class, playerCache);
        serviceLocator.register(PlayerSettingsService.class, playerSettingsService);

        // Register event listeners - MUST use plugin instance as container
        proxy.getEventManager().register(plugin, this);

        logger.info("PlayerDataFeature initialized for Velocity - using session cache");
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();

        // Run async to avoid blocking
        // Proxy no longer mutates persistent player data; backend handles core fields.
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        // Intentionally no-op: backend will close the session.
    }

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        // No-op; backend tracks detailed session state.
    }

    @Override
    public void shutdown() {
        // Unregister event listeners
        if (proxy != null && plugin != null) {
            proxy.getEventManager().unregisterListeners(plugin);
        }
        if (serviceLocator != null) {
            serviceLocator.unregister(PlayerCache.class);
            serviceLocator.unregister(PlayerSettingsService.class);
        }
        if (playerCacheExecutor != null) {
            playerCacheExecutor.shutdown();
            playerCacheExecutor = null;
        }
        playerCache = null;
        playerSettingsService = null;
        logger.info("Shutting down PlayerDataFeature for Velocity");
    }

    @Override
    public int getPriority() {
        return 50; // After DataAPI (20)
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final ThreadFactory delegate = Executors.defaultThreadFactory();
        private final String baseName;

        private NamedThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = delegate.newThread(r);
            thread.setName(baseName + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
