package sh.harold.fulcrum.velocity.rank;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.rank.NetworkRankVisualResolver;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

/**
 * Wires the proxy-side {@link RankService} and installs the network-config-driven visuals.
 */
public final class VelocityRankFeature implements VelocityFeature {
    private ServiceLocator serviceLocator;
    private RankService rankService;

    @Override
    public String getName() {
        return "Rank";
    }

    @Override
    public int getPriority() {
        // After session (25) and network config (18), before message-dependent features (25+)
        return 26;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.serviceLocator = serviceLocator;

        VelocityPlayerSessionService sessionService = serviceLocator.getService(VelocityPlayerSessionService.class).orElse(null);
        DataAPI dataAPI = serviceLocator.getService(DataAPI.class).orElse(null);
        NetworkConfigService configService = serviceLocator.getRequiredService(NetworkConfigService.class);

        this.rankService = new VelocityRankService(sessionService, dataAPI, logger);
        serviceLocator.register(RankService.class, rankService);

        Rank.setVisualResolver(new NetworkRankVisualResolver(configService));
        logger.info("VelocityRankFeature initialised: session={}, dataAPI={}",
                sessionService != null, dataAPI != null);
    }

    @Override
    public void shutdown() {
        Rank.setVisualResolver(null);
        if (serviceLocator != null) {
            serviceLocator.unregister(RankService.class);
        }
        rankService = null;
    }
}
