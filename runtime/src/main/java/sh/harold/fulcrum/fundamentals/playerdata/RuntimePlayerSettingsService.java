package sh.harold.fulcrum.fundamentals.playerdata;

import sh.harold.fulcrum.common.cache.PlayerCache;
import sh.harold.fulcrum.common.settings.PlayerDebugLevel;
import sh.harold.fulcrum.common.settings.PlayerSettingsService;
import sh.harold.fulcrum.common.settings.SettingLevel;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RuntimePlayerSettingsService implements PlayerSettingsService {

    private final PlayerCache playerCache;
    private final PlayerSessionService sessionService;

    public RuntimePlayerSettingsService(PlayerCache playerCache, PlayerSessionService sessionService) {
        this.playerCache = Objects.requireNonNull(playerCache, "playerCache");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    }

    @Override
    public CompletionStage<PlayerDebugLevel> getDebugLevel(UUID playerId) {
        PlayerDebugLevel level = sessionService.getDebugLevel(playerId);
        if (level.isEnabled()) {
            return CompletableFuture.completedFuture(level);
        }
        return CompletableFuture.completedFuture(readDebugLevel(playerId));
    }

    private static String normalizeVariant(String variant) {
        if (variant == null) {
            return null;
        }
        String normalized = variant.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    @Override
    public CompletionStage<SettingLevel> getLevel(UUID playerId, String key, SettingLevel fallback) {
        SettingLevel resolved = readEnumSetting(playerId, key, SettingLevel.class, fallback);
        return CompletableFuture.completedFuture(resolved);
    }

    private static String normalizeFamily(String family) {
        String normalizedFamily = Objects.requireNonNull(family, "family").trim();
        if (normalizedFamily.isEmpty()) {
            throw new IllegalArgumentException("family must not be empty");
        }
        return normalizedFamily.replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    @Override
    public CompletionStage<Void> setDebugLevel(UUID playerId, PlayerDebugLevel level) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        PlayerDebugLevel sanitized = PlayerDebugLevel.sanitize(level);
        sessionService.setDebugLevel(playerId, sanitized);
        playerCache.root(playerId).set("debug.level", sanitized.name());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> setLevel(UUID playerId, String key, SettingLevel level) {
        playerCache.root(playerId).set(key, level.name());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> CompletionStage<Optional<T>> getSetting(UUID playerId, String key, Class<T> type) {
        if (playerId == null || key == null || key.isBlank() || type == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<T> value = readPlayerSetting(playerId, key, type);
        return CompletableFuture.completedFuture(value);
    }

    @Override
    public CompletionStage<Void> setSetting(UUID playerId, String key, Object value) {
        if (playerId == null || key == null || key.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        playerCache.root(playerId).set(key, value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> removeSetting(UUID playerId, String key) {
        if (playerId == null || key == null || key.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        playerCache.root(playerId).remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public GameSettingsScope forScope(String family, String variant) {
        return new RuntimeScopedSettingsScope(playerCache, normalizeFamily(family), normalizeVariant(variant));
    }

    private <E extends Enum<E>> E readEnumSetting(UUID playerId, String path, Class<E> type, E fallback) {
        return playerCache.root(playerId)
                .get(path, String.class)
                .map(raw -> {
                    try {
                        return Enum.valueOf(type, raw);
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .orElse(fallback);
    }

    private <T> Optional<T> readPlayerSetting(UUID playerId, String path, Class<T> type) {
        return playerCache.root(playerId).get(path, type);
    }

    private PlayerDebugLevel readDebugLevel(UUID playerId) {
        return playerCache.root(playerId)
                .get("debug.level", String.class)
                .map(PlayerDebugLevel::from)
                .orElse(PlayerDebugLevel.NONE);
    }

    private record RuntimeScopedSettingsScope(PlayerCache playerCache, String family,
                                              String variant) implements GameSettingsScope {
            private RuntimeScopedSettingsScope(PlayerCache playerCache, String family, String variant) {
                this.playerCache = playerCache;
                this.family = Objects.requireNonNull(family, "family");
                this.variant = variant;
            }

            @Override
            public CompletionStage<Map<String, Object>> getAll(UUID playerId) {
                Map<String, Object> snapshot = playerCache.scoped(family, variant, playerId).snapshot();
                return CompletableFuture.completedFuture(Collections.unmodifiableMap(snapshot));
            }

            @Override
            public <T> CompletionStage<Optional<T>> get(UUID playerId, String key, Class<T> type) {
                Optional<T> value = playerCache.scoped(family, variant, playerId).get(key, type);
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public CompletionStage<Void> set(UUID playerId, String key, Object value) {
                playerCache.scoped(family, variant, playerId).set(key, value);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> remove(UUID playerId, String key) {
                playerCache.scoped(family, variant, playerId).remove(key);
                return CompletableFuture.completedFuture(null);
            }
        }
}
