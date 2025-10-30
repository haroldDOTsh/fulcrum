package sh.harold.fulcrum.runtime.message;

import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.debug.DebugTier;

import java.util.UUID;

public final class RuntimeDebugGate implements DebugGate {
    private final RankService rankService;

    public RuntimeDebugGate(RankService rankService) {
        this.rankService = rankService;
    }

    @Override
    public DebugTier tierFor(Audience audience) {
        if (audience instanceof Player player) {
            return tierFor(player.getUniqueId());
        }
        return DebugTier.NONE;
    }

    public DebugTier tierFor(UUID playerId) {
        if (playerId == null) {
            return DebugTier.NONE;
        }
        RankService service = resolveRankService();
        if (service == null) {
            return DebugTier.NONE;
        }
        try {
            Rank rank = service.getEffectiveRankSync(playerId);
            if (rank != null && rank.isStaff()) {
                return DebugTier.STAFF;
            }
        } catch (Exception ignored) {
        }
        return DebugTier.NONE;
    }

    private RankService resolveRankService() {
        if (rankService != null) {
            return rankService;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            return locator.findService(RankService.class).orElse(null);
        }
        return null;
    }
}
