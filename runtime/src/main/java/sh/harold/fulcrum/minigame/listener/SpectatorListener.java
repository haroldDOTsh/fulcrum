package sh.harold.fulcrum.minigame.listener;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Handles placeholder interactions for spectator utilities.
 */
public final class SpectatorListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.RED_BED) {
            return;
        }
        if (item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()
                && ChatColor.stripColor(item.getItemMeta().getDisplayName()).equalsIgnoreCase("Return to Lobby")) {
            event.getPlayer().sendMessage(ChatColor.GRAY + "Lobby warp coming soon; hang tight!");
            event.setCancelled(true);
        }
    }
}
