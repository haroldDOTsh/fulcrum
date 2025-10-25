package sh.harold.fulcrum.api.network;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of the active network profile stored in Redis and cached by runtimes.
 */
public record NetworkProfileView(
        String profileId,
        String tag,
        String serverIp,
        List<String> motd,
        ScoreboardCopy scoreboard,
        Map<String, RankVisualView> ranks,
        Instant updatedAt
) implements Serializable {

    public NetworkProfileView {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(serverIp, "serverIp");
        motd = List.copyOf(Objects.requireNonNull(motd, "motd"));
        scoreboard = Objects.requireNonNull(scoreboard, "scoreboard");
        ranks = Map.copyOf(Objects.requireNonNull(ranks, "ranks"));
        updatedAt = Objects.requireNonNullElse(updatedAt, Instant.EPOCH);
    }

    public Optional<RankVisualView> getRankVisual(String rankId) {
        if (rankId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ranks.get(rankId));
    }

    /**
     * Scoreboard title/footer pair shared across the network.
     */
    public record ScoreboardCopy(String title, String footer) implements Serializable {
        public ScoreboardCopy {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(footer, "footer");
        }
    }
}
