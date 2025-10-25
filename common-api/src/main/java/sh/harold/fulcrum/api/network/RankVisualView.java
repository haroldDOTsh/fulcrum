package sh.harold.fulcrum.api.network;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable view describing how a rank should be presented to players.
 */
public record RankVisualView(
        String displayName,
        String colorCode,
        String fullPrefix,
        String shortPrefix,
        String nameColor
) implements Serializable {

    public RankVisualView {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(colorCode, "colorCode");
        Objects.requireNonNull(fullPrefix, "fullPrefix");
        Objects.requireNonNull(shortPrefix, "shortPrefix");
        Objects.requireNonNull(nameColor, "nameColor");
    }
}
