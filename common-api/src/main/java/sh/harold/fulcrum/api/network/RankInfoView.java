package sh.harold.fulcrum.api.network;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Immutable view describing configurable tooltip + metadata for ranks.
 */
public record RankInfoView(
        String displayName,
        String fullPrefix,
        List<String> tooltipLines,
        String infoUrl
) implements Serializable {

    public RankInfoView {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(fullPrefix, "fullPrefix");
        tooltipLines = tooltipLines != null ? List.copyOf(tooltipLines) : List.of();
        infoUrl = infoUrl != null ? infoUrl : "";
    }
}
