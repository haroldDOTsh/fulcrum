package sh.harold.fulcrum.fundamentals.status;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.status.PlayerStatus;
import sh.harold.fulcrum.api.status.PresenceStatus;
import sh.harold.fulcrum.api.status.StatusService;
import sh.harold.fulcrum.api.status.StatusSnapshotMapper;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Status service backed by the runtime session cache with Mongo persistence for offline lookups.
 */
final class RuntimeStatusService implements StatusService {

    private static final String PLAYERS_COLLECTION = "players";
    private static final String SOCIAL_SECTION_KEY = "social";
    private static final String STATUS_KEY = "status";

    private final PlayerSessionService sessionService;
    private final DataAPI dataAPI;
    private final Executor executor;
    private final Logger logger;

    RuntimeStatusService(PlayerSessionService sessionService,
                         DataAPI dataAPI,
                         Executor executor,
                         Logger logger) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.dataAPI = dataAPI;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public CompletionStage<PlayerStatus> getStatus(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> resolveStatus(playerId), executor);
    }

    @Override
    public CompletionStage<Map<UUID, PlayerStatus>> getStatuses(Collection<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerStatus> resolved = new LinkedHashMap<>();
            for (UUID playerId : playerIds) {
                if (playerId == null) {
                    continue;
                }
                PlayerStatus status = resolveStatus(playerId);
                if (status != null) {
                    resolved.put(playerId, status);
                }
            }
            return resolved;
        }, executor);
    }

    @Override
    public CompletionStage<Void> updateStatus(UUID playerId, PresenceStatus presence, String activityBadge) {
        if (playerId == null || presence == null) {
            return CompletableFuture.completedFuture(null);
        }
        String badge = sanitizeBadge(activityBadge);
        long now = System.currentTimeMillis();
        PlayerStatus snapshot = new PlayerStatus(playerId, presence, badge, now);

        sessionService.withActiveSession(playerId, record -> applyToSession(record, snapshot));
        return persistAsync(snapshot);
    }

    private PlayerStatus resolveStatus(UUID playerId) {
        Optional<PlayerSessionRecord> session = sessionService.getActiveSession(playerId);
        if (session.isPresent()) {
            PlayerStatus status = extractStatus(playerId, session.get());
            if (status != null) {
                return normalise(status);
            }
        }
        PlayerStatus persisted = readFromMongo(playerId);
        return normalise(persisted != null ? persisted : new PlayerStatus(playerId, PresenceStatus.OFFLINE, null, 0L));
    }

    private PlayerStatus extractStatus(UUID playerId, PlayerSessionRecord record) {
        Object raw = record.getExtras().get(STATUS_KEY);
        return StatusSnapshotMapper.fromObject(playerId, raw);
    }

    private CompletionStage<Void> persistAsync(PlayerStatus status) {
        if (dataAPI == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> writeToMongo(status), executor)
                .exceptionally(throwable -> {
                    if (logger != null) {
                        logger.fine(() -> "Failed to persist status for " + status.playerId() + ": " + throwable.getMessage());
                    }
                    return null;
                });
    }

    private PlayerStatus readFromMongo(UUID playerId) {
        if (dataAPI == null) {
            return null;
        }
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
        if (!document.exists()) {
            return null;
        }
        Object social = document.get(SOCIAL_SECTION_KEY, null);
        if (!(social instanceof Map<?, ?> socialMap)) {
            return null;
        }
        Object status = socialMap.get(STATUS_KEY);
        return StatusSnapshotMapper.fromObject(playerId, status);
    }

    private void writeToMongo(PlayerStatus status) {
        Document document = dataAPI.collection(PLAYERS_COLLECTION).document(status.playerId().toString());
        Map<String, Object> social = new LinkedHashMap<>();
        Object rawSocial = document.get(SOCIAL_SECTION_KEY, null);
        if (rawSocial instanceof Map<?, ?> map) {
            map.forEach((key, value) -> social.put(String.valueOf(key), value));
        }
        social.put(STATUS_KEY, StatusSnapshotMapper.toMap(status));
        document.set(SOCIAL_SECTION_KEY, social);
    }

    private void applyToSession(PlayerSessionRecord record, PlayerStatus status) {
        record.getExtras().put(STATUS_KEY, StatusSnapshotMapper.toMap(status));
    }

    private PlayerStatus normalise(PlayerStatus status) {
        if (status == null) {
            return null;
        }
        PresenceStatus resolvedPresence = status.presence() == PresenceStatus.FAKE_OFFLINE
                ? PresenceStatus.OFFLINE
                : status.presence();
        return new PlayerStatus(
                status.playerId(),
                resolvedPresence,
                status.activityBadge(),
                status.updatedAtEpochMillis()
        );
    }

    private String sanitizeBadge(String badge) {
        if (badge == null) {
            return null;
        }
        String trimmed = badge.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

