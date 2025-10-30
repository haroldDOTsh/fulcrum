package sh.harold.fulcrum.velocity.message;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.audience.Audience;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.debug.DebugTier;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

public final class VelocityMessageDebugGate implements DebugGate {
    private final VelocityPlayerSessionService sessionService;
    private final DataAPI dataAPI;
    private final Logger logger;

    public VelocityMessageDebugGate(VelocityPlayerSessionService sessionService,
                                    DataAPI dataAPI,
                                    Logger logger) {
        this.sessionService = sessionService;
        this.dataAPI = dataAPI;
        this.logger = logger;
    }

    @Override
    public DebugTier tierFor(Audience audience) {
        if (audience instanceof Player player && dataAPI != null) {
            boolean staff = VelocityRankUtils.hasRankOrHigherSync(player, Rank.STAFF, sessionService, dataAPI, logger);
            return staff ? DebugTier.STAFF : DebugTier.NONE;
        }
        return DebugTier.NONE;
    }
}
