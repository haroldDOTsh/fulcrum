package sh.harold.fulcrum.fundamentals.status;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.status.PresenceStatus;
import sh.harold.fulcrum.api.status.StatusService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

/**
 * Wires the runtime status service and keeps presence in sync with join/quit events.
 */
public final class RuntimeStatusFeature implements PluginFeature, Listener {

    private JavaPlugin plugin;
    private StatusService statusService;
    private DependencyContainer container;
    private Logger logger;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        this.logger = plugin.getLogger();

        PlayerSessionService sessionService = container.getOptional(PlayerSessionService.class)
                .orElseThrow(() -> new IllegalStateException("PlayerSessionService is required before StatusFeature"));
        DataAPI dataAPI = container.getOptional(DataAPI.class).orElse(null);
        Executor executor = dataAPI != null ? dataAPI.executor() : ForkJoinPool.commonPool();

        this.statusService = new RuntimeStatusService(sessionService, dataAPI, executor, logger);

        container.register(StatusService.class, statusService);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.registerService(StatusService.class, statusService);
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        logger.info("StatusFeature initialized");
    }

    @Override
    public void shutdown() {
        if (container != null) {
            container.unregister(StatusService.class);
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.unregisterService(StatusService.class);
        }
        plugin = null;
        statusService = null;
        logger = null;
    }

    @Override
    public int getPriority() {
        return 45; // After sessions so player data can consume the service
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        statusService.updateStatus(playerId, PresenceStatus.ONLINE, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        statusService.updateStatus(playerId, PresenceStatus.OFFLINE, null);
    }
}
