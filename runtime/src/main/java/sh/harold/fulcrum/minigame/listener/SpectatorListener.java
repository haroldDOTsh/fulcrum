package sh.harold.fulcrum.minigame.listener;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Handles placeholder interactions for spectator utilities.
 */
public final class SpectatorListener implements Listener {
    private static final String RETURN_TO_LOBBY_LABEL = "Return to Lobby (Right Click)";
    private static final String QUEUE_AGAIN_LABEL = "Queue Again (Right Click)";

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }

        String stripped = ChatColor.stripColor(meta.getDisplayName());
        if (item.getType() == Material.RED_BED && RETURN_TO_LOBBY_LABEL.equalsIgnoreCase(stripped)) {
            event.getPlayer().sendMessage(ChatColor.GRAY + "Lobby warp coming soon; hang tight!");
            event.setCancelled(true);
            return;
        }

        if (item.getType() == Material.PAPER && QUEUE_AGAIN_LABEL.equalsIgnoreCase(stripped)) {
            event.getPlayer().sendMessage(ChatColor.GRAY + "Queue rejoin coming soon; hang tight!");
            event.setCancelled(true);
        }
    }
}
