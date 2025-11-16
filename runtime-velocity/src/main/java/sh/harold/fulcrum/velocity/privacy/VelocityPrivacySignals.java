package sh.harold.fulcrum.velocity.privacy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import sh.harold.fulcrum.api.party.PartySnapshot;
import sh.harold.fulcrum.common.privacy.PrivacySignals;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.party.PartyService;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class VelocityPrivacySignals implements PrivacySignals {
    private final ProxyServer proxy;
    private final ServiceLocator serviceLocator;

    VelocityPrivacySignals(ProxyServer proxy, ServiceLocator serviceLocator) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.serviceLocator = Objects.requireNonNull(serviceLocator, "serviceLocator");
    }

    @Override
    public CompletionStage<Boolean> shareServer(UUID actorId, UUID targetId) {
        return CompletableFuture.completedFuture(onSameServer(actorId, targetId));
    }

    @Override
    public CompletionStage<Boolean> shareParty(UUID actorId, UUID targetId) {
        return CompletableFuture.completedFuture(inSameParty(actorId, targetId));
    }

    private boolean onSameServer(UUID actorId, UUID targetId) {
        if (actorId == null || targetId == null) {
            return false;
        }
        Optional<Player> actor = proxy.getPlayer(actorId);
        Optional<Player> target = proxy.getPlayer(targetId);
        if (actor.isEmpty() || target.isEmpty()) {
            return false;
        }
        Optional<String> actorServer = actor.get().getCurrentServer().map(connection -> connection.getServerInfo().getName());
        Optional<String> targetServer = target.get().getCurrentServer().map(connection -> connection.getServerInfo().getName());
        return actorServer.isPresent()
                && targetServer.isPresent()
                && actorServer.get().equalsIgnoreCase(targetServer.get());
    }

    private boolean inSameParty(UUID actorId, UUID targetId) {
        Optional<PartyService> partyService = serviceLocator.getService(PartyService.class);
        if (partyService.isEmpty()) {
            return false;
        }
        Optional<PartySnapshot> actorParty = partyService.get().getPartyByPlayer(actorId);
        Optional<PartySnapshot> targetParty = partyService.get().getPartyByPlayer(targetId);
        return actorParty.isPresent()
                && targetParty.isPresent()
                && Objects.equals(actorParty.get().getPartyId(), targetParty.get().getPartyId());
    }
}
