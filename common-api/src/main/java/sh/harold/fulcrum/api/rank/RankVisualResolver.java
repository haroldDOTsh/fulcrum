package sh.harold.fulcrum.api.rank;

import sh.harold.fulcrum.api.network.RankVisualView;

@FunctionalInterface
public interface RankVisualResolver {
    RankVisualView resolve(Rank rank);
}
