package sh.harold.fulcrum.api.network;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Map-backed network profile snapshot with path-based accessors.
 */
public final class NetworkProfileView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Map<String, Object> attributes = new LinkedHashMap<>();

    @JsonCreator
    public NetworkProfileView(@JsonProperty("profileId") String profileId) {
        if (profileId != null) {
            attributes.put("profileId", profileId);
        }
    }

    public String profileId() {
        Object id = attributes.get("profileId");
        if (id == null) {
            throw new IllegalStateException("profileId missing from network profile view");
        }
        return id.toString();
    }

    public Optional<String> getString(String path) {
        return getValue(path, String.class).map(Object::toString);
    }

    public <T> Optional<T> getValue(String path, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object node = resolvePath(path);
        if (node == null) {
            return Optional.empty();
        }
        if (type.isInstance(node)) {
            return Optional.of(type.cast(node));
        }
        if (type == String.class) {
            return Optional.of(type.cast(node.toString()));
        }
        return Optional.empty();
    }

    public List<String> getStringList(String path) {
        Object value = resolvePath(path);
        if (value instanceof List<?> list) {
            return list.stream().map(Objects::toString).toList();
        }
        return List.of();
    }

    public Optional<RankVisualView> getRankVisual(String rankId) {
        if (rankId == null) {
            return Optional.empty();
        }
        Object raw = getMap("ranks").get(rankId);
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Map<String, Object> visual = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                visual.put(k.toString(), v);
            }
        });
        return Optional.of(new RankVisualView(
                Objects.toString(visual.getOrDefault("displayName", "")),
                Objects.toString(visual.getOrDefault("colorCode", "")),
                Objects.toString(visual.getOrDefault("fullPrefix", "")),
                Objects.toString(visual.getOrDefault("shortPrefix", "")),
                Objects.toString(visual.getOrDefault("nameColor", ""))
        ));
    }

    public Optional<RankInfoView> getRankInfo(String rankId) {
        if (rankId == null || rankId.isBlank()) {
            return Optional.empty();
        }
        String basePath = "rankInfo." + rankId;
        Optional<String> displayName = getString(basePath + ".displayName");
        Optional<String> fullPrefix = getString(basePath + ".fullPrefix");
        List<String> tooltipLines = getStringList(basePath + ".tooltipLines");
        Optional<String> infoUrl = getString(basePath + ".infoUrl");

        if (displayName.isEmpty()
                && fullPrefix.isEmpty()
                && tooltipLines.isEmpty()
                && infoUrl.isEmpty()) {
            return Optional.empty();
        }

        Optional<RankVisualView> visual = getRankVisual(rankId);
        String resolvedDisplayName = displayName.filter(value -> !value.isBlank())
                .orElseGet(() -> visual.map(RankVisualView::displayName).orElse(""));
        String resolvedPrefix = fullPrefix.filter(value -> !value.isBlank())
                .orElseGet(() -> visual.map(RankVisualView::fullPrefix).orElse(""));

        return Optional.of(new RankInfoView(
                resolvedDisplayName,
                resolvedPrefix,
                tooltipLines,
                infoUrl.orElse("")
        ));
    }

    public Instant updatedAt() {
        Object value = resolvePath("updatedAt");
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String str) {
            try {
                return Instant.parse(str);
            } catch (Exception ignored) {
            }
        }
        return Instant.EPOCH;
    }

    @JsonAnyGetter
    public Map<String, Object> data() {
        return Map.copyOf(attributes);
    }

    @JsonAnySetter
    public void putAttribute(String key, Object value) {
        if (key != null) {
            attributes.put(key, value);
        }
    }

    public Map<String, Object> getMap(String path) {
        Object value = resolvePath(path);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    copy.put(k.toString(), v);
                }
            });
            return copy;
        }
        return Map.of();
    }

    private Object resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = attributes;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(part);
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
}
