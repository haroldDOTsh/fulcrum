package sh.harold.fulcrum.playerdata;

import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.EventHandler;
import java.util.UUID;

public final class PlayerDataLifecycleListener implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerProfileManager.load(uuid);
        PlayerDataRegistry.notifyJoin(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerDataRegistry.notifyQuit(uuid);
        PlayerProfileManager.unload(uuid);
    }
}
