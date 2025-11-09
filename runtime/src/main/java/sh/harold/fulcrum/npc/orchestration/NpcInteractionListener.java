package sh.harold.fulcrum.npc.orchestration;

import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCDamageByEntityEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Routes Citizens interaction events into the behaviour dispatcher.
 */
public final class NpcInteractionListener implements Listener {
    private final PoiNpcOrchestrator orchestrator;

    public NpcInteractionListener(PoiNpcOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @EventHandler
    public void onClick(NPCClickEvent event) {
        handleInteraction(event, event.getClicker());
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        handleInteraction(event, event.getClicker());
    }

    @EventHandler
    public void onLeftClick(NPCLeftClickEvent event) {
        handleInteraction(event, event.getClicker());
    }

    @EventHandler
    public void onDamage(NPCDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            handleInteraction(event, player);
            event.setCancelled(true);
        }
    }

    private void handleInteraction(net.citizensnpcs.api.event.NPCEvent event, Player player) {
        if (player == null) {
            return;
        }
        NPC npc = event.getNPC();
        orchestrator.handleInteraction(npc.getUniqueId(), player);
    }
}
