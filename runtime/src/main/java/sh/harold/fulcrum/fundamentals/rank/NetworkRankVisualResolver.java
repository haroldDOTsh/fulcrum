package sh.harold.fulcrum.fundamentals.rank;

import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.RankVisualView;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankVisualResolver;

final class NetworkRankVisualResolver implements RankVisualResolver {
    private final NetworkConfigService configService;

    NetworkRankVisualResolver(NetworkConfigService configService) {
        this.configService = configService;
    }

    @Override
    public RankVisualView resolve(Rank rank) {
        return configService.getRankVisual(rank.name())
                .orElseGet(rank::defaultVisual);
    }
}
