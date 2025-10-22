package sh.harold.fulcrum.fundamentals.playerdata;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.common.settings.SettingLevel;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Paper-side implementation of the shared player settings facade.
 */
public class RuntimePlayerSettingsService implements PlayerSettingsService {
    private static final String PLAYERS_COLLECTION = "players";
    private static final String SETTINGS_PREFIX = "settings";

    private final DataAPI dataAPI;
    private final PlayerSessionService sessionService;

    public RuntimePlayerSettingsService(DataAPI dataAPI, PlayerSessionService sessionService) {
        this.dataAPI = Objects.requireNonNull(dataAPI, "dataAPI");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Override
    public CompletionStage<Boolean> isDebugEnabled(UUID playerId) {
        boolean enabled = sessionService.isDebugEnabled(playerId);
        if (enabled) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.completedFuture(readBooleanSetting(playerId, "debug.enabled", false));
    }

    @Override
    public CompletionStage<Void> setDebugEnabled(UUID playerId, boolean enabled) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        sessionService.setDebugEnabled(playerId, enabled);
        writePlayerSetting(playerId, "debug.enabled", enabled);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<SettingLevel> getLevel(UUID playerId, String key, SettingLevel fallback) {
        SettingLevel resolved = readEnumSetting(playerId, key, SettingLevel.class, fallback);
        return CompletableFuture.completedFuture(resolved);
    }

    @Override
    public CompletionStage<Void> setLevel(UUID playerId, String key, SettingLevel level) {
        writePlayerSetting(playerId, key, level.name());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public GameSettingsScope forGame(String gameId) {
        return new RuntimeGameSettingsScope(dataAPI, gameId);
    }

    private boolean readBooleanSetting(UUID playerId, String path, boolean fallback) {
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        if (!document.exists()) {
            return fallback;
        }
        Object value = document.get(SETTINGS_PREFIX + "." + path, null);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private <E extends Enum<E>> E readEnumSetting(UUID playerId, String path, Class<E> type, E fallback) {
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        if (!document.exists()) {
            return fallback;
        }
        Object value = document.get(SETTINGS_PREFIX + "." + path, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private void writePlayerSetting(UUID playerId, String path, Object value) {
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        if (!document.exists()) {
            Map<String, Object> seeded = new LinkedHashMap<>();
            seeded.put(SETTINGS_PREFIX, new LinkedHashMap<>());
            dataAPI.collection(PLAYERS_COLLECTION).create(playerId.toString(), seeded);
            document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        }
        document.set(SETTINGS_PREFIX + "." + path, value);
    }

    private record RuntimeGameSettingsScope(DataAPI dataAPI, String collectionName) implements GameSettingsScope {
            private RuntimeGameSettingsScope(DataAPI dataAPI, String collectionName) {
                this.dataAPI = dataAPI;
                this.collectionName = "minigame_" + collectionName;
            }

            @Override
            public CompletionStage<Map<String, Object>> getAll(UUID playerId) {
                Document document = document(playerId);
                if (!document.exists()) {
                    return CompletableFuture.completedFuture(Collections.emptyMap());
                }
                Object raw = document.get(SETTINGS_PREFIX);
                if (raw instanceof Map<?, ?> map) {
                    return CompletableFuture.completedFuture(Collections.unmodifiableMap(castMap(map)));
                }
                return CompletableFuture.completedFuture(Collections.emptyMap());
            }

            @Override
            public <T> CompletionStage<Optional<T>> get(UUID playerId, String key, Class<T> type) {
                Document document = document(playerId);
                if (!document.exists()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                Object value = document.get(SETTINGS_PREFIX + "." + key, null);
                if (type.isInstance(value)) {
                    return CompletableFuture.completedFuture(Optional.of(type.cast(value)));
                }
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletionStage<Void> set(UUID playerId, String key, Object value) {
                Document document = ensureDocument(playerId);
                document.set(SETTINGS_PREFIX + "." + key, value);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> remove(UUID playerId, String key) {
                Document document = document(playerId);
                if (document.exists()) {
                    Map<String, Object> settings = castMap(document.get(SETTINGS_PREFIX));
                    if (settings.remove(key) != null) {
                        document.set(SETTINGS_PREFIX, settings);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }

            private Document document(UUID playerId) {
                return dataAPI.collection(collectionName).document(playerId.toString());
            }

            private Document ensureDocument(UUID playerId) {
                Document document = document(playerId);
                if (!document.exists()) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(SETTINGS_PREFIX, new LinkedHashMap<>());
                    dataAPI.collection(collectionName).create(playerId.toString(), payload);
                    document = document(playerId);
                }
                return document;
            }

            @SuppressWarnings("unchecked")
            private Map<String, Object> castMap(Object raw) {
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    return copy;
                }
                return new LinkedHashMap<>();
            }
        }
}
