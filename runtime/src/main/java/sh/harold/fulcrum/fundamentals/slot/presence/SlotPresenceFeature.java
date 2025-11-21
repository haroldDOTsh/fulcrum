package sh.harold.fulcrum.fundamentals.slot.presence;

import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.actionflag.VanishService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.Optional;

/**
 * Wires the runtime-wide slot presence service into the DI container.
 */
public final class SlotPresenceFeature implements PluginFeature, Listener {
    private SlotPresenceService presenceService;

    @Override
    public int getPriority() {
        // After routing/session, before minigame/chat/tab consumers rely on isolation
        return 190;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        VanishService vanishService = container.getOptional(VanishService.class)
                .orElseGet(() -> Optional.ofNullable(ServiceLocatorImpl.getInstance())
                        .flatMap(locator -> locator.findService(VanishService.class))
                        .orElse(null));

        presenceService = new SlotPresenceService(plugin, vanishService);
        container.register(SlotPresenceService.class, presenceService);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(SlotPresenceService.class, presenceService));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(SlotPresenceService.class));
        if (presenceService != null) {
            presenceService.shutdown();
            presenceService = null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (presenceService != null) {
            presenceService.recordPlayerName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (presenceService != null) {
            presenceService.unbindPlayer(event.getPlayer().getUniqueId());
        }
    }
}
