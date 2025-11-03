package sh.harold.fulcrum.velocity.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.common.settings.PlayerDebugLevel;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Proxy-side session cache access. Creates or updates session envelopes in Redis so backend servers
 * can attach without reloading MongoDB.
 */
public class VelocityPlayerSessionService {

    private static final String SESSION_KEY_PREFIX = "fulcrum:player:";
    private static final String STATE_SUFFIX = ":state";

    private final LettuceSessionRedisClient redisClient;
    private final ObjectMapper objectMapper;
    private final Logger logger;
    private final String proxyId;

    private final ConcurrentHashMap<UUID, String> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerSessionRecord> localCache = new ConcurrentHashMap<>();

    public VelocityPlayerSessionService(LettuceSessionRedisClient redisClient, ObjectMapper objectMapper, Logger logger, String proxyId) {
        this.redisClient = redisClient;
        this.objectMapper = objectMapper;
        this.logger = logger;
        this.proxyId = proxyId;
    }

    public PlayerSessionHandle attachOrCreateSession(UUID playerId, Map<String, Object> bootstrapState) {
        PlayerSessionRecord existing = fetch(playerId).orElse(null);
        if (existing != null) {
            activeSessions.put(playerId, existing.getSessionId());
            existing.setServerId(proxyId);
            mergeBootstrap(existing, bootstrapState, false);
            persist(existing);
            return new PlayerSessionHandle(playerId, existing.getSessionId(), false);
        }

        String sessionId = UUID.randomUUID().toString();
        PlayerSessionRecord record = PlayerSessionRecord.newSession(playerId, sessionId, proxyId);
        mergeBootstrap(record, bootstrapState, true);
        persist(record);
        activeSessions.put(playerId, sessionId);
        return new PlayerSessionHandle(playerId, sessionId, true);
    }

    public boolean withActiveSession(UUID playerId, Consumer<PlayerSessionRecord> consumer) {
        String expected = activeSessions.get(playerId);
        if (expected == null) {
            return false;
        }
        Optional<PlayerSessionRecord> recordOpt = fetch(playerId);
        if (recordOpt.isEmpty()) {
            return false;
        }

        PlayerSessionRecord record = recordOpt.get();
        if (!expected.equals(record.getSessionId())) {
            return false;
        }

        try {
            consumer.accept(record);
            record.touch();
            persist(record);
            return true;
        } catch (Exception e) {
            logger.error("Failed to mutate proxy session for {}", playerId, e);
            return false;
        }
    }

    public Optional<PlayerSessionRecord> endSession(UUID playerId, String sessionId) {
        String expected = activeSessions.get(playerId);
        if (expected == null || !expected.equals(sessionId)) {
            return Optional.empty();
        }

        Optional<PlayerSessionRecord> record = fetch(playerId)
                .filter(r -> sessionId.equals(r.getSessionId()));

        activeSessions.remove(playerId);
        record.ifPresent(this::remove);
        return record;
    }

    public Optional<PlayerSessionRecord> getSession(UUID playerId) {
        return fetch(playerId);
    }

    public PlayerDebugLevel getDebugLevel(UUID playerId) {
        return getSession(playerId)
                .map(PlayerSessionRecord::getDebugLevel)
                .orElse(PlayerDebugLevel.NONE);
    }

    public boolean isDebugEnabled(UUID playerId) {
        return getDebugLevel(playerId).isEnabled();
    }

    public void setDebugEnabled(UUID playerId, boolean enabled) {
        setDebugLevel(playerId, enabled ? PlayerDebugLevel.PLAYER : PlayerDebugLevel.NONE);
    }

    public void setDebugLevel(UUID playerId, PlayerDebugLevel level) {
        withActiveSession(playerId, record -> record.setDebugLevel(level));
    }

    @SuppressWarnings("unchecked")
    private void mergeBootstrap(PlayerSessionRecord record, Map<String, Object> bootstrap, boolean overwrite) {
        if (bootstrap == null || bootstrap.isEmpty()) {
            return;
        }

        Map<String, Object> core = record.getCore();
        if (overwrite) {
            core.clear();
        }

        bootstrap.forEach((key, value) -> {
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
            if ("playtime".equals(key) && value instanceof Map<?, ?> map) {
                record.setPlaytime(copyNestedMap(map));
                return;
            }
            if ("extras".equals(key) && value instanceof Map<?, ?> map) {
                Map<String, Object> extrasCopy = copyNestedMap(map);
                record.getExtras().clear();
                record.getExtras().putAll(extrasCopy);
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

    private Optional<PlayerSessionRecord> fetch(UUID playerId) {
        PlayerSessionRecord cached = localCache.get(playerId);
        if (cached != null) {
            return Optional.of(cached);
        }

        if (!redisClient.isAvailable()) {
            return Optional.empty();
        }

        String json = redisClient.get(stateKey(playerId));
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }

        try {
            PlayerSessionRecord record = objectMapper.readValue(json, PlayerSessionRecord.class);
            localCache.put(playerId, record);
            return Optional.of(record);
        } catch (JsonProcessingException e) {
            logger.error("Failed to decode session payload for {}", playerId, e);
            return Optional.empty();
        }
    }

    private void persist(PlayerSessionRecord record) {
        record.touch();
        localCache.put(record.getPlayerId(), record);
        if (!redisClient.isAvailable()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(record);
            redisClient.set(stateKey(record.getPlayerId()), json, 0);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialise session for {}", record.getPlayerId(), e);
        }
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

    private void remove(PlayerSessionRecord record) {
        localCache.remove(record.getPlayerId());
        if (redisClient.isAvailable()) {
            redisClient.delete(stateKey(record.getPlayerId()));
        }
    }

    private String stateKey(UUID playerId) {
        return SESSION_KEY_PREFIX + playerId + STATE_SUFFIX;
    }

    private Map<String, Object> copyNestedMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            String stringKey = String.valueOf(key);
            if (value instanceof Map<?, ?> nested) {
                copy.put(stringKey, copyNestedMap(nested));
            } else if (value instanceof List<?> list) {
                copy.put(stringKey, new ArrayList<>(list));
            } else {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }

    public record PlayerSessionHandle(UUID playerId, String sessionId, boolean createdNew) {
    }
}
