package sh.harold.fulcrum.session;

import com.fasterxml.jackson.annotation.*;
import sh.harold.fulcrum.common.settings.PlayerDebugLevel;

import java.time.Instant;
import java.util.*;

/**
 * Mutable representation of a player's in-session state cached in Redis.
 * Serialised as JSON using Jackson for consistency with the message bus payloads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlayerSessionRecord {

    private static final String DEFAULT_RANK = "DEFAULT";
    private final Map<String, Object> core = new HashMap<>();
    private final Map<String, Object> rank = new HashMap<>();
    private final Map<String, Object> minigames = new HashMap<>();
    private final Map<String, Object> extras = new HashMap<>();
    private final Map<String, Map<String, Object>> scopedData = new HashMap<>();
    private final Map<String, Object> playtime = new LinkedHashMap<>();
    private final List<Segment> segments = new ArrayList<>();
    private UUID playerId;
    private String sessionId;
    private String serverId;
    private Integer clientProtocolVersion;
    private String clientBrand;
    private long createdAt;
    private long lastUpdatedAt;
    @JsonIgnore
    private Segment activeSegment;

    public PlayerSessionRecord() {
        long now = Instant.now().toEpochMilli();
        this.createdAt = now;
        this.lastUpdatedAt = now;
    }

    public static PlayerSessionRecord newSession(UUID playerId, String sessionId, String serverId) {
        PlayerSessionRecord record = new PlayerSessionRecord();
        record.playerId = Objects.requireNonNull(playerId, "playerId");
        record.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        record.serverId = serverId;
        return record;
    }

    private static String newSegmentId() {
        return UUID.randomUUID().toString();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    @JsonProperty("clientProtocolVersion")
    public Integer getClientProtocolVersion() {
        return clientProtocolVersion;
    }

    public void setClientProtocolVersion(Integer clientProtocolVersion) {
        this.clientProtocolVersion = clientProtocolVersion;
    }

    @JsonProperty("clientBrand")
    public String getClientBrand() {
        return clientBrand;
    }

    public void setClientBrand(String clientBrand) {
        if (clientBrand != null && clientBrand.isBlank()) {
            this.clientBrand = null;
        } else {
            this.clientBrand = clientBrand;
        }
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(long lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    @JsonProperty("core")
    public Map<String, Object> getCore() {
        return core;
    }

    @JsonProperty("rank")
    public Map<String, Object> getRank() {
        return rank;
    }

    @JsonProperty("minigames")
    public Map<String, Object> getMinigames() {
        return minigames;
    }

    @JsonIgnore
    public Map<String, Object> getSettings() {
        Map<String, Object> settings = getSettingsInternal(false);
        return settings == null ? Collections.emptyMap() : Collections.unmodifiableMap(settings);
    }

    @JsonIgnore
    public Map<String, Object> mutableSettings() {
        return getSettingsInternal(true);
    }

    @JsonIgnore
    public PlayerDebugLevel getDebugLevel() {
        Map<String, Object> settings = getSettingsInternal(false);
        if (settings == null) {
            return PlayerDebugLevel.NONE;
        }
        Object debug = settings.get("debug");
        if (debug instanceof Map<?, ?> debugMap) {
            Object level = debugMap.get("level");
            if (level != null) {
                return PlayerDebugLevel.from(level);
            }
        }
        return PlayerDebugLevel.NONE;
    }

    @JsonIgnore
    public void setDebugLevel(PlayerDebugLevel level) {
        PlayerDebugLevel sanitized = PlayerDebugLevel.sanitize(level);
        Map<String, Object> settings = getSettingsInternal(true);
        Map<String, Object> debugSection = toMutableMap(settings.get("debug"));
        debugSection.put("level", sanitized.name());
        settings.put("debug", debugSection);
    }

    @JsonIgnore
    public boolean isDebugEnabled() {
        return getDebugLevel().isEnabled();
    }

    @JsonIgnore
    public void setDebugEnabled(boolean enabled) {
        setDebugLevel(enabled ? PlayerDebugLevel.PLAYER : PlayerDebugLevel.NONE);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras;
    }

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    @JsonProperty("segments")
    public List<Segment> getSegments() {
        return segments;
    }

    @JsonProperty("segments")
    public void setSegments(List<Segment> segments) {
        this.segments.clear();
        if (segments != null) {
            this.segments.addAll(segments);
            this.segments.forEach(segment -> {
                if (segment.getSegmentId() == null || segment.getSegmentId().isBlank()) {
                    segment.setSegmentId(newSegmentId());
                }
            });
        }
        rebuildActiveSegment();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSettingsInternal(boolean create) {
        Object value = core.get("settings");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = toMutableMap(map);
            core.put("settings", converted);
            return converted;
        }
        if (create) {
            Map<String, Object> created = new LinkedHashMap<>();
            core.put("settings", created);
            return created;
        }
        return null;
    }

    private Map<String, Object> toMutableMap(Object source) {
        if (!(source instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    @JsonProperty("scoped")
    public Map<String, Map<String, Object>> getScopedData() {
        return scopedData;
    }

    @JsonProperty("scoped")
    public void setScopedData(Map<String, Map<String, Object>> data) {
        scopedData.clear();
        if (data != null) {
            data.forEach((family, value) -> {
                if (family != null && !family.isBlank() && value != null) {
                    scopedData.put(family, new LinkedHashMap<>(value));
                }
            });
        }
    }

    @JsonIgnore
    public Map<String, Object> ensureScopedFamily(String family) {
        Map<String, Object> familyState = scopedData.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
        familyState.putIfAbsent("settings", new LinkedHashMap<>());
        return familyState;
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getScopedSettings(String family) {
        Map<String, Object> familyState = ensureScopedFamily(family);
        Object settings = familyState.get("settings");
        if (settings instanceof Map<?, ?> map) {
            Map<String, Object> typed = toMutableMap(map);
            familyState.put("settings", typed);
            return typed;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        familyState.put("settings", created);
        return created;
    }

    @JsonProperty("playtime")
    public Map<String, Object> getPlaytime() {
        return playtime;
    }

    @JsonProperty("playtime")
    public void setPlaytime(Map<String, Object> data) {
        playtime.clear();
        if (data != null) {
            playtime.putAll(toMutableMap(data));
        }
    }

    @JsonIgnore
    public Map<String, Object> mutablePlaytime() {
        return playtime;
    }

    @JsonIgnore
    public void touch() {
        this.lastUpdatedAt = Instant.now().toEpochMilli();
    }

    @JsonIgnore
    public void endActiveSegment(long endedAt) {
        if (activeSegment != null) {
            if (endedAt < activeSegment.getStartedAt()) {
                endedAt = activeSegment.getStartedAt();
            }
            activeSegment.setEndedAt(endedAt);
            activeSegment = null;
        }
    }

    @JsonIgnore
    public void closeSegmentsIfNeeded(long endedAt) {
        endActiveSegment(endedAt);
    }

    @JsonIgnore
    public void rebuildActiveSegment() {
        activeSegment = null;
        if (segments.isEmpty()) {
            return;
        }
        Segment last = segments.get(segments.size() - 1);
        if (last.getEndedAt() == null) {
            activeSegment = last;
        }
    }

    @JsonIgnore
    public void startSegment(String type,
                             String context,
                             String serverId,
                             Map<String, Object> metadata,
                             long startedAt) {
        endActiveSegment(startedAt);

        Segment segment = new Segment();
        segment.setType(type);
        segment.setContext(context);
        segment.setServerId(serverId);
        segment.setStartedAt(startedAt);
        segment.setSegmentId(newSegmentId());
        if (metadata != null && !metadata.isEmpty()) {
            segment.getMetadata().putAll(metadata);
        }
        segments.add(segment);
        activeSegment = segment;
    }

    @JsonIgnore
    public Segment getActiveSegment() {
        return activeSegment;
    }

    @JsonIgnore
    public Map<String, Object> exportForPersistence() {
        Map<String, Object> persisted = new HashMap<>(core);
        if (shouldPersistRank()) {
            Map<String, Object> rankCopy = new HashMap<>(rank);
            persisted.put("rankInfo", rankCopy);
            Object primary = rankCopy.get("primary");
            if (primary != null && !DEFAULT_RANK.equalsIgnoreCase(primary.toString())) {
                persisted.put("rank", primary);
            }
        }
        if (!minigames.isEmpty()) {
            persisted.put("minigames", new HashMap<>(minigames));
        }
        persisted.put("lastUpdatedAt", lastUpdatedAt);
        if (clientProtocolVersion != null) {
            persisted.put("clientProtocolVersion", clientProtocolVersion);
        }
        if (clientBrand != null) {
            persisted.put("clientBrand", clientBrand);
        }
        return persisted;
    }

    @JsonIgnore
    public boolean shouldPersistRank() {
        return shouldPersistRank(rank);
    }

    private boolean shouldPersistRank(Map<String, Object> rankInfo) {
        if (rankInfo == null || rankInfo.isEmpty()) {
            return false;
        }

        Object primary = rankInfo.get("primary");
        boolean hasNonDefaultPrimary = primary != null && !DEFAULT_RANK.equalsIgnoreCase(primary.toString());

        Object all = rankInfo.get("all");
        boolean hasNonDefaultRanks = false;
        if (all instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value != null && !DEFAULT_RANK.equalsIgnoreCase(value.toString())) {
                    hasNonDefaultRanks = true;
                    break;
                }
            }
        }

        boolean hasMetadata = rankInfo.entrySet().stream()
                .anyMatch(entry -> {
                    String key = entry.getKey();
                    return !"primary".equals(key) && !"all".equals(key);
                });

        return hasNonDefaultPrimary || hasNonDefaultRanks || hasMetadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Segment {
        private String segmentId;
        private String type;
        private String context;
        private String serverId;
        private long startedAt;
        private Long endedAt;
        private Map<String, Object> metadata = new HashMap<>();

        @JsonProperty("segmentId")
        public String getSegmentId() {
            return segmentId;
        }

        public void setSegmentId(String segmentId) {
            this.segmentId = segmentId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(long startedAt) {
            this.startedAt = startedAt;
        }

        public Long getEndedAt() {
            return endedAt;
        }

        public void setEndedAt(Long endedAt) {
            this.endedAt = endedAt;
        }

        @JsonProperty("metadata")
        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }
    }
}
