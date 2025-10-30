package sh.harold.fulcrum.api.rank;

import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.RankVisualView;

/**
 * Resolves rank visuals using the active network configuration profile.
 */
public final class NetworkRankVisualResolver implements RankVisualResolver {
    private final NetworkConfigService configService;

    public NetworkRankVisualResolver(NetworkConfigService configService) {
        this.configService = configService;
    }

    @Override
    public RankVisualView resolve(Rank rank) {
        return configService.getRankVisual(rank.name())
                .orElseGet(rank::defaultVisual);
    }
}
