package sh.harold.fulcrum.fundamentals.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.data.playtime.PlaytimeTracker;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles player session state stored in Redis (with in-memory fallback).
 * Provides session tokens to guard against delayed disconnect events.
 */
public class PlayerSessionService {

    private static final Logger LOGGER = Logger.getLogger(PlayerSessionService.class.getName());
    private static final String SESSION_KEY_PREFIX = "fulcrum:player:";
    private static final String STATE_SUFFIX = ":state";
    private static final String HANDOFF_SUFFIX = ":handoff";
    private static final Duration DEFAULT_HANDOFF_TTL = Duration.ofSeconds(15);

    private final LettuceRedisOperations redisOperations;
    private final ObjectMapper objectMapper;
    private final boolean redisAvailable;
    private final String fallbackServerId;
    private final String fallbackEnvironment;
    private final ServerIdentifier serverIdentifier;
    private final PlaytimeTracker playtimeTracker;

    private final ConcurrentHashMap<UUID, String> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerSessionRecord> localRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, HandoffRecord> localHandoffs = new ConcurrentHashMap<>();

    public PlayerSessionService(LettuceRedisOperations redisOperations,
                                ObjectMapper objectMapper,
                                String fallbackServerId,
                                String fallbackEnvironment,
                                ServerIdentifier serverIdentifier,
                                PlaytimeTracker playtimeTracker) {
        this.redisOperations = redisOperations;
        this.objectMapper = objectMapper;
        this.fallbackServerId = fallbackServerId;
        this.fallbackEnvironment = fallbackEnvironment;
        this.serverIdentifier = serverIdentifier;
        this.playtimeTracker = playtimeTracker;
        this.redisAvailable = redisOperations != null && redisOperations.isAvailable();
    }

    public PlayerSessionHandle attachOrCreateSession(UUID playerId, Map<String, Object> baseState) {
        PlayerSessionRecord record = fetchRecord(playerId).orElse(null);
        if (record != null) {
            activeSessions.put(playerId, record.getSessionId());
            record.setServerId(currentServerId());
            mergeBaseState(record, baseState, false);
            ensureEnvironment(record);
            persistRecord(record);
            return new PlayerSessionHandle(playerId, record.getSessionId(), false);
        }

        String sessionId = UUID.randomUUID().toString();
        record = PlayerSessionRecord.newSession(playerId, sessionId, currentServerId());
        mergeBaseState(record, baseState, true);
        ensureEnvironment(record);
        persistRecord(record);
        activeSessions.put(playerId, sessionId);
        LOGGER.fine(() -> "Created new session " + sessionId + " for " + playerId);
        return new PlayerSessionHandle(playerId, sessionId, true);
    }

    public Optional<PlayerSessionRecord> getActiveSession(UUID playerId) {
        String sessionId = activeSessions.get(playerId);
        if (sessionId == null) {
            return Optional.empty();
        }
        return fetchRecord(playerId).filter(record -> sessionId.equals(record.getSessionId()));
    }

    public boolean withActiveSession(UUID playerId, Consumer<PlayerSessionRecord> mutation) {
        String sessionId = activeSessions.get(playerId);
        if (sessionId == null) {
            return false;
        }

        Optional<PlayerSessionRecord> optionalRecord = fetchRecord(playerId);
        if (optionalRecord.isEmpty()) {
            return false;
        }

        PlayerSessionRecord record = optionalRecord.get();
        if (!sessionId.equals(record.getSessionId())) {
            return false;
        }

        try {
            mutation.accept(record);
            record.touch();
            persistRecord(record);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mutate session for " + playerId, e);
            return false;
        }
    }

    public Optional<PlayerSessionRecord> endSession(UUID playerId, String proposedSessionId) {
        String sessionId = activeSessions.get(playerId);
        if (sessionId == null || !sessionId.equals(proposedSessionId)) {
            return Optional.empty();
        }

        Optional<PlayerSessionRecord> record = fetchRecord(playerId)
                .filter(r -> proposedSessionId.equals(r.getSessionId()))
                .map(r -> {
                    r.closeSegmentsIfNeeded(System.currentTimeMillis());
                    if (playtimeTracker != null) {
                        playtimeTracker.recordCompletedSegments(r);
                    }
                    return r;
                });

        activeSessions.remove(playerId);
        record.ifPresent(this::removeRecord);
        clearHandoff(playerId);
        clearMinigameContext(playerId);
        return record;
    }

    public void clearLocalCache() {
        localRecords.clear();
        activeSessions.clear();
        localHandoffs.clear();
    }

    public void startServerSegment(UUID playerId) {
        String serverId = currentServerId();
        String environment = currentEnvironment();
        Map<String, Object> metadata = new HashMap<>();
        if (environment != null) {
            metadata.put("environment", environment);
        }
        metadata.put("serverId", serverId);
        String context = environment != null ? environment : serverId;
        startSegment(playerId, "SERVER", context, metadata, serverId);
    }

    public void startSegment(UUID playerId,
                             String type,
                             String context,
                             Map<String, Object> metadata,
                             String serverId) {
        withActiveSession(playerId, record -> {
            long now = System.currentTimeMillis();
            Map<String, Object> enriched = new HashMap<>();
            if (metadata != null) {
                metadata.forEach((key, value) -> {
                    if (value != null) {
                        enriched.put(key, value);
                    }
                });
            }
            String environment = currentEnvironment();
            enriched.putIfAbsent("environment", environment);
            ensureEnvironment(record);
            record.setServerId(environment != null ? environment : serverId);
            // surface logical family/variant fallback on the segment itself
            if (!enriched.containsKey("family")) {
                Object active = record.getMinigames().get("active");
                if (active instanceof Map<?, ?> activeMap) {
                    Object family = activeMap.get("family");
                    if (family != null) {
                        enriched.put("family", family);
                    }
                    Object variant = activeMap.get("variant");
                    if (variant != null) {
                        enriched.put("variant", variant);
                    }
                }
            }

            // we no longer care about the physical serverId; persist the logical context instead
            record.startSegment(type, context, environment, enriched, now);
        });
    }

    public void endActiveSegment(UUID playerId) {
        withActiveSession(playerId, record -> {
            long now = System.currentTimeMillis();
            PlayerSessionRecord.Segment active = record.getActiveSegment();
            record.endActiveSegment(now);
            if (playtimeTracker != null && active != null) {
                playtimeTracker.recordSegment(record, active);
            }
        });
    }

    public boolean isDebugEnabled(UUID playerId) {
        return getActiveSession(playerId)
                .map(PlayerSessionRecord::isDebugEnabled)
                .orElse(false);
    }

    public void setDebugEnabled(UUID playerId, boolean enabled) {
        withActiveSession(playerId, record -> record.setDebugEnabled(enabled));
    }

    public void updateActiveSegmentMetadata(UUID playerId, Consumer<Map<String, Object>> mutator) {
        if (mutator == null) {
            return;
        }
        withActiveSession(playerId, record -> {
            PlayerSessionRecord.Segment active = record.getActiveSegment();
            if (active != null) {
                mutator.accept(active.getMetadata());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void mergeBaseState(PlayerSessionRecord record, Map<String, Object> baseState, boolean overwrite) {
        if (baseState == null || baseState.isEmpty()) {
            return;
        }

        Map<String, Object> core = record.getCore();
        if (overwrite) {
            core.clear();
        }

        baseState.forEach((key, value) -> {
            if ("rankInfo".equals(key) && value instanceof Map<?, ?> map) {
                record.getRank().clear();
                map.forEach((k, v) -> record.getRank().put(String.valueOf(k), v));
                return;
            }
            if ("rank".equals(key) && value != null) {
                record.getRank().putIfAbsent("primary", value);
                return;
            }
            if ("minigames".equals(key) && value instanceof Map<?, ?> map) {
                record.getMinigames().clear();
                map.forEach((k, v) -> record.getMinigames().put(String.valueOf(k), v));
                return;
            }
            if ("settings".equals(key) && value instanceof Map<?, ?> map) {
                Map<String, Object> settingsCopy = copyNestedMap(map);
                if (settingsCopy != null) {
                    core.put("settings", settingsCopy);
                }
                return;
            }
            if ("clientProtocolVersion".equals(key)) {
                record.setClientProtocolVersion(parseProtocolVersion(value));
                return;
            }
            if ("clientBrand".equals(key)) {
                record.setClientBrand(value != null ? value.toString() : null);
                return;
            }
            if (value != null) {
                if (overwrite) {
                    core.put(key, value);
                } else {
                    core.putIfAbsent(key, value);
                }
            }
        });
    }

    private Map<String, Object> copyNestedMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            String stringKey = String.valueOf(key);
            if (value instanceof Map<?, ?> nestedMap) {
                copy.put(stringKey, copyNestedMap(nestedMap));
            } else if (value instanceof List<?> list) {
                copy.put(stringKey, new ArrayList<>(list));
            } else {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }

    private Integer parseProtocolVersion(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Optional<PlayerSessionRecord> fetchRecord(UUID playerId) {
        PlayerSessionRecord local = localRecords.get(playerId);
        if (local != null) {
            return Optional.of(local);
        }

        if (!redisAvailable) {
            return Optional.empty();
        }

        String json = redisOperations.get(stateKey(playerId));
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }

        try {
            PlayerSessionRecord record = objectMapper.readValue(json, PlayerSessionRecord.class);
            localRecords.put(playerId, record);
            return Optional.of(record);
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.SEVERE, "Failed to parse session payload for " + playerId, e);
            return Optional.empty();
        }
    }

    private void persistRecord(PlayerSessionRecord record) {
        record.touch();
        localRecords.put(record.getPlayerId(), record);

        if (!redisAvailable) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(record);
            redisOperations.set(stateKey(record.getPlayerId()), json, 0);
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.WARNING, "Failed to serialise session for " + record.getPlayerId(), e);
        }
    }

    private void removeRecord(PlayerSessionRecord record) {
        localRecords.remove(record.getPlayerId());
        if (redisAvailable) {
            redisOperations.delete(stateKey(record.getPlayerId()));
        }
    }

    public void recordHandoff(UUID playerId,
                              String serverId,
                              String slotId,
                              String reservationToken,
                              Map<String, String> metadata,
                              Duration ttl) {
        if (playerId == null) {
            return;
        }
        Duration effectiveTtl = ttl != null ? ttl : DEFAULT_HANDOFF_TTL;
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + effectiveTtl.toMillis();

        HandoffRecord record = new HandoffRecord(
                playerId,
                serverId,
                slotId,
                reservationToken,
                metadata,
                issuedAt,
                expiresAt
        );
        localHandoffs.put(playerId, record);

        if (!redisAvailable) {
            return;
        }

        try {
            long ttlSeconds = Math.max(1, effectiveTtl.toSeconds());
            String payload = objectMapper.writeValueAsString(record);
            redisOperations.set(handoffKey(playerId), payload, ttlSeconds);
        } catch (JsonProcessingException exception) {
            LOGGER.log(Level.WARNING, "Failed to serialise handoff marker for " + playerId, exception);
        }
    }

    public Optional<HandoffRecord> getHandoff(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }

        HandoffRecord cached = localHandoffs.get(playerId);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached);
        }
        localHandoffs.remove(playerId);

        if (!redisAvailable) {
            return Optional.empty();
        }

        String payload = redisOperations.get(handoffKey(playerId));
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }

        try {
            HandoffRecord record = objectMapper.readValue(payload, HandoffRecord.class);
            if (record.isExpired()) {
                clearHandoff(playerId);
                return Optional.empty();
            }
            localHandoffs.put(playerId, record);
            return Optional.of(record);
        } catch (JsonProcessingException exception) {
            LOGGER.log(Level.WARNING, "Failed to decode handoff marker for " + playerId, exception);
            return Optional.empty();
        }
    }

    public void clearHandoff(UUID playerId) {
        if (playerId == null) {
            return;
        }
        localHandoffs.remove(playerId);
        if (redisAvailable) {
            redisOperations.delete(handoffKey(playerId));
        }
    }

    public void updateMinigameContext(UUID playerId, Map<String, String> metadata, String slotId) {
        withActiveSession(playerId, record -> {
            ensureEnvironment(record);
            record.getMinigames().remove("active");
            record.getMinigames().remove("lastSlotId");
        });
    }

    public void clearMinigameContext(UUID playerId) {
        withActiveSession(playerId, record -> {
            record.getMinigames().remove("active");
            record.getMinigames().remove("lastSlotId");
        });
    }

    public void setActiveMatchId(UUID playerId, UUID matchId) {
        withActiveSession(playerId, record -> {
            if (matchId != null) {
                record.getMinigames().put("lastMatchId", matchId.toString());
            } else {
                record.getMinigames().remove("lastMatchId");
            }
        });
    }

    public void clearTrackedMatch(UUID playerId) {
        withActiveSession(playerId, record -> record.getMinigames().remove("lastMatchId"));
    }

    public Optional<PlayerSessionRecord> getSession(UUID playerId) {
        return fetchRecord(playerId);
    }

    public void releaseSession(UUID playerId) {
        if (playerId == null) {
            return;
        }
        activeSessions.remove(playerId);
        localRecords.remove(playerId);
    }

    public String getLocalServerId() {
        return currentServerId();
    }

    private String stateKey(UUID playerId) {
        return SESSION_KEY_PREFIX + playerId + STATE_SUFFIX;
    }

    private String handoffKey(UUID playerId) {
        return SESSION_KEY_PREFIX + playerId + HANDOFF_SUFFIX;
    }

    private String currentServerId() {
        if (serverIdentifier != null) {
            String resolved = serverIdentifier.getServerId();
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return fallbackServerId;
    }

    private String currentEnvironment() {
        if (serverIdentifier != null) {
            String role = serverIdentifier.getRole();
            if (role != null && !role.isBlank()) {
                return role;
            }
        }
        return fallbackEnvironment;
    }

    private void ensureEnvironment(PlayerSessionRecord record) {
        String environment = currentEnvironment();
        if (environment != null && !environment.isBlank()) {
            record.getCore().put("environment", environment);
        }
    }

    public record PlayerSessionHandle(UUID playerId, String sessionId, boolean createdNew) {
    }

    public static class HandoffRecord {
        private UUID playerId;
        private String serverId;
        private String slotId;
        private String reservationToken;
        private Map<String, String> metadata;
        private long issuedAt;
        private long expiresAt;

        public HandoffRecord() {
        }

        public HandoffRecord(UUID playerId,
                             String serverId,
                             String slotId,
                             String reservationToken,
                             Map<String, String> metadata,
                             long issuedAt,
                             long expiresAt) {
            this.playerId = playerId;
            this.serverId = serverId;
            this.slotId = slotId;
            this.reservationToken = reservationToken;
            this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getServerId() {
            return serverId;
        }

        public String getSlotId() {
            return slotId;
        }

        public String getReservationToken() {
            return reservationToken;
        }

        public Map<String, String> getMetadata() {
            return metadata != null ? metadata : Map.of();
        }

        public long getIssuedAt() {
            return issuedAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        @JsonIgnore
        public boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }
    }
}
