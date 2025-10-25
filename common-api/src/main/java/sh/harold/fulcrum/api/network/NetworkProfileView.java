package sh.harold.fulcrum.api.network;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Immutable snapshot of the active network profile.
 * Exposes both strongly typed accessors for common fields and a generic map for extensibility.
 */
public final class NetworkProfileView implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String profileId;
    private final String tag;
    private final String serverIp;
    private final List<String> motd;
    private final ScoreboardCopy scoreboard;
    private final Map<String, RankVisualView> ranks;
    private final Instant updatedAt;
    @JsonIgnore
    private final Map<String, Object> attributes;

    @JsonCreator
    public NetworkProfileView(
            @JsonProperty("profileId") String profileId,
            @JsonProperty("tag") String tag,
            @JsonProperty("serverIp") String serverIp,
            @JsonProperty("motd") List<String> motd,
            @JsonProperty("scoreboard") ScoreboardCopy scoreboard,
            @JsonProperty("ranks") Map<String, RankVisualView> ranks,
            @JsonProperty("updatedAt") Instant updatedAt
    ) {
        this(profileId, tag, serverIp, motd, scoreboard, ranks, updatedAt, null);
    }

    public NetworkProfileView(String profileId,
                              String tag,
                              String serverIp,
                              List<String> motd,
                              ScoreboardCopy scoreboard,
                              Map<String, RankVisualView> ranks,
                              Instant updatedAt,
                              Map<String, Object> attributes) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.tag = tag == null || tag.isBlank() ? profileId : tag;
        this.serverIp = Objects.requireNonNull(serverIp, "serverIp");
        this.motd = List.copyOf(Objects.requireNonNull(motd, "motd"));
        this.scoreboard = Objects.requireNonNull(scoreboard, "scoreboard");
        this.ranks = Map.copyOf(Objects.requireNonNull(ranks, "ranks"));
        this.updatedAt = Objects.requireNonNullElse(updatedAt, Instant.EPOCH);
        this.attributes = attributes != null
                ? deepImmutable(attributes)
                : buildAttributeMap();
    }

    public String profileId() {
        return profileId;
    }

    public String tag() {
        return tag;
    }

    public String serverIp() {
        return serverIp;
    }

    public List<String> motd() {
        return motd;
    }

    public ScoreboardCopy scoreboard() {
        return scoreboard;
    }

    public Map<String, RankVisualView> ranks() {
        return ranks;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Generic accessor for string values.
     */
    public Optional<String> getString(String path) {
        return getValue(path, String.class).map(Object::toString);
    }

    /**
     * Generic accessor for values using dot-notation paths.
     */
    public <T> Optional<T> getValue(String path, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object value = resolvePath(path);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        if (type == String.class) {
            return Optional.of(type.cast(value.toString()));
        }
        return Optional.empty();
    }

    /**
     * @return unmodifiable view of the raw profile document.
     */
    public Map<String, Object> data() {
        return attributes;
    }

    public Optional<RankVisualView> getRankVisual(String rankId) {
        if (rankId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ranks.get(rankId));
    }

    private Object resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = attributes;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }

            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Map<String, Object> buildAttributeMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("profileId", profileId);
        root.put("tag", tag);
        root.put("serverIp", serverIp);
        root.put("motd", motd);

        Map<String, Object> scoreboardSection = new LinkedHashMap<>();
        scoreboardSection.put("title", scoreboard.title());
        scoreboardSection.put("footer", scoreboard.footer());
        root.put("scoreboard", Map.copyOf(scoreboardSection));

        Map<String, Object> rankSection = new LinkedHashMap<>();
        ranks.forEach((rankId, visual) -> {
            Map<String, Object> visualMap = new LinkedHashMap<>();
            visualMap.put("displayName", visual.displayName());
            visualMap.put("colorCode", visual.colorCode());
            visualMap.put("fullPrefix", visual.fullPrefix());
            visualMap.put("shortPrefix", visual.shortPrefix());
            visualMap.put("nameColor", visual.nameColor());
            rankSection.put(rankId, Map.copyOf(visualMap));
        });
        root.put("ranks", Map.copyOf(rankSection));
        root.put("updatedAt", updatedAt);
        return Map.copyOf(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepImmutable(Map<String, Object> input) {
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                copy.put(key, deepImmutable((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copy.put(key, deepImmutableList(list));
            } else {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NetworkProfileView that)) {
            return false;
        }
        return profileId.equals(that.profileId)
                && tag.equals(that.tag)
                && serverIp.equals(that.serverIp)
                && motd.equals(that.motd)
                && scoreboard.equals(that.scoreboard)
                && ranks.equals(that.ranks)
                && updatedAt.equals(that.updatedAt)
                && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, tag, serverIp, motd, scoreboard, ranks, updatedAt, attributes);
    }

    @Override
    public String toString() {
        return "NetworkProfileView{" +
                "profileId='" + profileId + '\'' +
                ", tag='" + tag + '\'' +
                ", serverIp='" + serverIp + '\'' +
                ", updatedAt=" + updatedAt +
                '}';
    }

    private List<?> deepImmutableList(List<?> list) {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object value : list) {
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) map;
                copy.add(deepImmutable(child));
            } else if (value instanceof List<?> childList) {
                copy.add(deepImmutableList(childList));
            } else {
                copy.add(value);
            }
        }
        return List.copyOf(copy);
    }

    public record ScoreboardCopy(String title, String footer) implements Serializable {
        public ScoreboardCopy {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(footer, "footer");
        }
    }
}
