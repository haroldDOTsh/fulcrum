package sh.harold.fulcrum.velocity.message;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.audience.Audience;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.debug.DebugTier;

public final class VelocityMessageDebugGate implements DebugGate {
    private final RankService rankService;
    private final Logger logger;

    public VelocityMessageDebugGate(RankService rankService,
                                    Logger logger) {
        this.rankService = rankService;
        this.logger = logger;
    }

    @Override
    public DebugTier tierFor(Audience audience) {
        if (audience instanceof Player player && rankService != null) {
            try {
                Rank rank = rankService.getEffectiveRankSync(player.getUniqueId());
                boolean staff = rank != null && rank.getPriority() >= Rank.STAFF.getPriority();
                return staff ? DebugTier.STAFF : DebugTier.NONE;
            } catch (Exception ex) {
                logger.debug("Failed to resolve debug tier for {}", player.getUniqueId(), ex);
            }
        }
        return DebugTier.NONE;
    }
}
