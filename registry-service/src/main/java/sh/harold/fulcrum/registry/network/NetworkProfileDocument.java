package sh.harold.fulcrum.registry.network;

import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.network.RankVisualView;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

record NetworkProfileDocument(
        String profileId,
        String tag,
        String serverIp,
        List<String> motd,
        String scoreboardTitle,
        String scoreboardFooter,
        Map<String, RankVisualDocument> ranks,
        Instant updatedAt,
        Map<String, Object> rawData
) {

    NetworkProfileDocument {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(serverIp, "serverIp");
        motd = List.copyOf(Objects.requireNonNull(motd, "motd"));
        scoreboardTitle = Objects.requireNonNullElse(scoreboardTitle, "");
        scoreboardFooter = Objects.requireNonNullElse(scoreboardFooter, "");
        ranks = Map.copyOf(Objects.requireNonNull(ranks, "ranks"));
        updatedAt = Objects.requireNonNullElse(updatedAt, Instant.EPOCH);
        rawData = Map.copyOf(Objects.requireNonNull(rawData, "rawData"));
    }

    NetworkProfileDocument withUpdatedAt(Instant newTimestamp) {
        return new NetworkProfileDocument(profileId, tag, serverIp, motd,
                scoreboardTitle, scoreboardFooter, ranks, newTimestamp, rawData);
    }

    NetworkProfileView toView() {
        Map<String, RankVisualView> visuals = ranks.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().toView(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        NetworkProfileView.ScoreboardCopy scoreboard =
                new NetworkProfileView.ScoreboardCopy(scoreboardTitle, scoreboardFooter);

        return new NetworkProfileView(
                profileId,
                tag,
                serverIp,
                motd,
                scoreboard,
                visuals,
                updatedAt,
                rawData
        );
    }

    record RankVisualDocument(
            String displayName,
            String colorCode,
            String fullPrefix,
            String shortPrefix,
            String nameColor
    ) {
        RankVisualDocument {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(colorCode, "colorCode");
            Objects.requireNonNull(fullPrefix, "fullPrefix");
            Objects.requireNonNull(shortPrefix, "shortPrefix");
            Objects.requireNonNull(nameColor, "nameColor");
        }

        RankVisualView toView() {
            return new RankVisualView(displayName, colorCode, fullPrefix, shortPrefix, nameColor);
        }
    }
}
