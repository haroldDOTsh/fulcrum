package sh.harold.fulcrum.minigame.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Handles placeholder interactions for spectator utilities.
 */
public final class SpectatorListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.RED_BED) {
            return;
        }
        if (item.hasItemMeta()
            && item.getItemMeta().hasDisplayName()
            && isReturnToLobby(item.getItemMeta().displayName())) {
            event.getPlayer().sendMessage(Component.text("Lobby warp coming soon; hang tight!", NamedTextColor.GRAY));
            event.setCancelled(true);
        }
    }

    private boolean isReturnToLobby(Component displayName) {
        return displayName != null
            && PLAIN_TEXT.serialize(displayName).equalsIgnoreCase("Return to Lobby");
    }
}
