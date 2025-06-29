package sh.harold.fulcrum.feature.identity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import sh.harold.fulcrum.api.data.registry.PlayerProfileManager;

import java.util.UUID;

public class IdentityListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        var profile = PlayerProfileManager.get(uuid);
        if (profile == null) return;
        IdentityData data = profile.get(IdentityData.class);
        boolean changed = false;
        if (!player.getName().equals(data.displayname)) {
            data.displayname = player.getName();
            changed = true;
        }
        long now = System.currentTimeMillis();
        if (data.firstLogin == 0L) {
            data.firstLogin = now;
            changed = true;
        }
        data.lastLogin = now;
        changed = true;
        if (changed) { //TODO: fix logic
            profile.save(IdentityData.class, data);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        var profile = PlayerProfileManager.get(uuid);
        if (profile == null) return;
        IdentityData data = profile.get(IdentityData.class);
        long now = System.currentTimeMillis();
        data.lastLogout = now;
        profile.save(IdentityData.class, data);
    }
}
