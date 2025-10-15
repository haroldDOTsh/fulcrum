package sh.harold.fulcrum.session;

import com.fasterxml.jackson.annotation.*;

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
    private final List<Segment> segments = new ArrayList<>();
    private UUID playerId;
    private String sessionId;
    private String serverId;
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

    private static String newSegmentId() {
        return UUID.randomUUID().toString();
    }

    @JsonIgnore
    public void touch() {
        this.lastUpdatedAt = Instant.now().toEpochMilli();
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
