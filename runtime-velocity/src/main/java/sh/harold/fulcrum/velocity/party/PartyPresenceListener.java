package sh.harold.fulcrum.velocity.party;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;

final class PartyPresenceListener {
    private final PartyService partyService;
    private final Logger logger;
    private final Set<UUID> localPlayers;

    PartyPresenceListener(PartyService partyService, Logger logger, Set<UUID> localPlayers) {
        this.partyService = partyService;
        this.logger = logger;
        this.localPlayers = localPlayers;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        try {
            partyService.refreshPresence(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), true);
            localPlayers.add(event.getPlayer().getUniqueId());
        } catch (Exception ex) {
            logger.warn("Failed to refresh party presence for {}", event.getPlayer().getUsername(), ex);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            partyService.refreshPresence(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), false);
            localPlayers.remove(event.getPlayer().getUniqueId());
        } catch (Exception ex) {
            logger.warn("Failed to refresh party presence for {}", event.getPlayer().getUsername(), ex);
        }
    }
}
