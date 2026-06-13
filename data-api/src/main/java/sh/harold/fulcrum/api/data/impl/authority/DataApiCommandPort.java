package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Legacy persistence bridge for the private authority command surface.
 */
public final class DataApiCommandPort implements DataAuthority.CommandPort {
    private static final String PLAYERS_COLLECTION = "players";
    private static final String RANKS_COLLECTION = "player_ranks";
    private static final String COMMANDS_COLLECTION = "authority_commands";
    private static final String COMMAND_KEYS_COLLECTION = "authority_command_keys";
    private static final String MATCHES_COLLECTION = "match_records";
    private static final String MATCH_PARTICIPANTS_COLLECTION = "match_participant_stats";

    private final DataAPI dataAPI;

    public DataApiCommandPort(DataAPI dataAPI) {
        this.dataAPI = dataAPI;
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.CommandEnvelope command) {
        return findExistingResult(command).thenCompose(existing -> {
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
            if (isExpired(command)) {
                return completeAndRecord(command, rejected(command, DataAuthority.RejectionReason.EXPIRED_DEADLINE,
                    "Command deadline expired"));
            }

            CompletionStage<DataAuthority.CommandResult> result = switch (command.type()) {
                case RECORD_PLAYER_LOGIN, START_SESSION -> persistPlayerLogin(command);
                case RENEW_SESSION -> persistPlayerTouch(command);
                case RECORD_PLAYER_LOGOUT, END_SESSION -> persistPlayerLogout(command);
                case GRANT_RANK, REVOKE_RANK -> persistRankProjection(command);
                case RECORD_MATCH_START -> persistMatchStart(command);
                case RECORD_MATCH_END -> persistMatchEnd(command);
            };
            return result.thenCompose(commandResult -> completeAndRecord(command, commandResult));
        });
    }

    private CompletionStage<DataAuthority.CommandResult> persistPlayerLogin(DataAuthority.CommandEnvelope command) {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Player command scope must be player:<uuid>"));
        }

        Collection players = dataAPI.collection(PLAYERS_COLLECTION);
        String id = playerId.toString();
        long now = longValue(command.payload().get("timestamp"), System.currentTimeMillis());

        return players.selectAsync(id).thenCompose(document -> {
            Map<String, Object> data = new HashMap<>(document.toMap());
            boolean exists = document.exists();
            long previousLastJoin = longValue(data.get("lastJoin"), 0L);

            data.put("uuid", id);
            data.put("username", stringValue(command.payload().get("username"), stringValue(data.get("username"), "")));
            data.putIfAbsent("firstJoin", now);
            data.put("lastJoin", now);
            data.put("lastSeen", now);
            data.put("online", booleanValue(command.payload().get("online"), true));

            int joinCount = intValue(data.get("joinCount"), 0);
            if (!exists || now - previousLastJoin > 30000L) {
                data.put("joinCount", joinCount + 1);
            }

            data.putIfAbsent("totalPlaytime", 0L);
            copyPresent(command.payload(), data, "lastWorld", "lastLocation", "gamemode", "level", "exp",
                "health", "foodLevel", "lastIp", "lastProxySession", "protocolVersion", "currentServer",
                "lastServerSwitch", "sessionId", "currentProxy");

            return players.createAsync(id, data)
                .thenApply(ignored -> accepted(command));
        }).exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<DataAuthority.CommandResult> persistPlayerLogout(DataAuthority.CommandEnvelope command) {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Player command scope must be player:<uuid>"));
        }

        Collection players = dataAPI.collection(PLAYERS_COLLECTION);
        String id = playerId.toString();
        long now = longValue(command.payload().get("timestamp"), System.currentTimeMillis());

        return players.selectAsync(id).thenCompose(document -> {
            if (!document.exists()) {
                return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.VALIDATION_FAILED,
                    "Player document does not exist"));
            }

            Map<String, Object> data = new HashMap<>(document.toMap());
            data.put("lastSeen", now);
            data.put("online", false);
            copyPresent(command.payload(), data, "lastWorld", "lastLocation", "gamemode");

            if (booleanValue(command.payload().get("clearCurrentServer"), false)) {
                data.put("currentServer", null);
            }

            String startField = stringValue(command.payload().get("playtimeStartField"), "lastJoin");
            long sessionStart = longValue(data.get(startField), 0L);
            if (sessionStart > 0L) {
                long totalPlaytime = longValue(data.get("totalPlaytime"), 0L);
                data.put("totalPlaytime", totalPlaytime + Math.max(0L, now - sessionStart));
            }

            return players.createAsync(id, data)
                .thenApply(ignored -> accepted(command));
        }).exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<DataAuthority.CommandResult> persistPlayerTouch(DataAuthority.CommandEnvelope command) {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Player command scope must be player:<uuid>"));
        }

        Collection players = dataAPI.collection(PLAYERS_COLLECTION);
        String id = playerId.toString();
        long now = longValue(command.payload().get("timestamp"), System.currentTimeMillis());

        return players.selectAsync(id).thenCompose(document -> {
            if (!document.exists()) {
                return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.VALIDATION_FAILED,
                    "Player document does not exist"));
            }

            Map<String, Object> data = new HashMap<>(document.toMap());
            data.put("lastSeen", now);
            copyPresent(command.payload(), data, "currentServer", "lastServerSwitch", "protocolVersion");

            return players.createAsync(id, data)
                .thenApply(ignored -> accepted(command));
        }).exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<DataAuthority.CommandResult> persistRankProjection(DataAuthority.CommandEnvelope command) {
        UUID playerId = playerId(command);
        if (playerId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Rank command scope must be player:<uuid>"));
        }

        Object ranks = command.payload().get("ranks");
        Object primaryRank = command.payload().get("primaryRank");
        if (ranks == null || primaryRank == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.VALIDATION_FAILED,
                "Rank command requires ranks and primaryRank"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("uuid", playerId.toString());
        data.put("primary_rank", primaryRank.toString());
        data.put("ranks", ranks);

        return dataAPI.collection(RANKS_COLLECTION)
            .createAsync(playerId.toString(), data)
            .thenApply(ignored -> accepted(command))
            .exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<DataAuthority.CommandResult> persistMatchStart(DataAuthority.CommandEnvelope command) {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Match command scope must be match:<uuid>"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("matchId", matchId.toString());
        data.put("familyId", stringValue(command.payload().get("familyId"), "unknown"));
        data.put("state", stringValue(command.payload().get("state"), "STARTED"));
        data.put("startedAt", longValue(command.payload().get("startedAt"), System.currentTimeMillis()));
        data.put("metadata", command.payload());
        copyPresent(command.payload(), data, "mapId", "serverId", "slotId");

        return dataAPI.collection(MATCHES_COLLECTION)
            .createAsync(matchId.toString(), data)
            .thenApply(ignored -> accepted(command))
            .exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<DataAuthority.CommandResult> persistMatchEnd(DataAuthority.CommandEnvelope command) {
        UUID matchId = matchId(command);
        if (matchId == null) {
            return CompletableFuture.completedFuture(rejected(command, DataAuthority.RejectionReason.INVALID_SCOPE,
                "Match command scope must be match:<uuid>"));
        }

        Collection matches = dataAPI.collection(MATCHES_COLLECTION);
        String id = matchId.toString();
        long endedAt = longValue(command.payload().get("endedAt"), System.currentTimeMillis());

        return matches.selectAsync(id).thenCompose(document -> {
            Map<String, Object> data = new HashMap<>(document.toMap());
            data.put("matchId", id);
            data.put("familyId", stringValue(command.payload().get("familyId"), stringValue(data.get("familyId"), "unknown")));
            data.put("state", stringValue(command.payload().get("state"), "ENDED"));
            data.putIfAbsent("startedAt", longValue(command.payload().get("startedAt"), endedAt));
            data.put("endedAt", endedAt);
            data.put("metadata", command.payload());
            copyPresent(command.payload(), data, "mapId", "serverId", "slotId");

            return matches.createAsync(id, data)
                .thenCompose(ignored -> persistMatchParticipants(matchId, command.payload().get("participants")))
                .thenApply(ignored -> accepted(command));
        }).exceptionally(error -> rejected(command, DataAuthority.RejectionReason.STORE_UNAVAILABLE, error.getMessage()));
    }

    private CompletionStage<Void> persistMatchParticipants(UUID matchId, Object rawParticipants) {
        if (!(rawParticipants instanceof Iterable<?> participants)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        Collection participantCollection = dataAPI.collection(MATCH_PARTICIPANTS_COLLECTION);
        for (Object raw : participants) {
            if (!(raw instanceof Map<?, ?> participant)) {
                continue;
            }
            Object rawPlayerId = participant.get("playerId");
            if (rawPlayerId == null) {
                continue;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId.toString());
            data.put("playerId", rawPlayerId.toString());
            Object state = participant.get("state");
            if (state != null) {
                data.put("state", state.toString());
            }
            Object stats = participant.get("stats");
            data.put("stats", stats instanceof Map<?, ?> ? stats : Map.of());

            String documentId = matchId + "_" + rawPlayerId;
            chain = chain.thenCompose(ignored -> participantCollection.createAsync(documentId, data).thenApply(doc -> null));
        }
        return chain;
    }

    private static UUID playerId(DataAuthority.CommandEnvelope command) {
        Object payloadId = command.payload().get("playerId");
        String raw = payloadId == null ? command.scope() : payloadId.toString();
        if (raw.startsWith("player:")) {
            raw = raw.substring("player:".length());
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID matchId(DataAuthority.CommandEnvelope command) {
        Object payloadId = command.payload().get("matchId");
        String raw = payloadId == null ? command.scope() : payloadId.toString();
        if (raw.startsWith("match:")) {
            raw = raw.substring("match:".length());
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private CompletionStage<Document> recordCommand(
        DataAuthority.CommandEnvelope command,
        DataAuthority.CommandResult result
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("commandId", command.commandId().toString());
        data.put("type", command.type().name());
        data.put("actorId", command.actorId());
        data.put("scope", command.scope());
        data.put("idempotencyKey", command.idempotencyKey());
        data.put("deadlineEpochMillis", command.deadlineEpochMillis());
        data.put("fencingToken", command.fencingToken());
        data.put("expectedRevision", command.expectedRevision());
        data.put("payload", command.payload());
        data.put("accepted", result.accepted());
        data.put("revision", result.revision());
        data.put("rejectionReason", result.rejectionReason().name());
        data.put("message", result.message());
        data.put("recordedAtEpochMillis", System.currentTimeMillis());

        return dataAPI.collection(COMMANDS_COLLECTION).createAsync(command.commandId().toString(), data);
    }

    private CompletionStage<DataAuthority.CommandResult> completeAndRecord(
        DataAuthority.CommandEnvelope command,
        DataAuthority.CommandResult result
    ) {
        return recordCommand(command, result)
            .thenCompose(ignored -> recordIdempotencyKey(command, result))
            .handle((ignored, auditError) -> result);
    }

    private CompletionStage<Void> recordIdempotencyKey(
        DataAuthority.CommandEnvelope command,
        DataAuthority.CommandResult result
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("commandId", command.commandId().toString());
        data.put("accepted", result.accepted());
        data.put("revision", result.revision());
        data.put("rejectionReason", result.rejectionReason().name());
        data.put("message", result.message());
        data.put("recordedAtEpochMillis", System.currentTimeMillis());

        return dataAPI.collection(COMMAND_KEYS_COLLECTION)
            .createAsync(safeIdempotencyKey(command.idempotencyKey()), data)
            .thenApply(ignored -> null);
    }

    private CompletionStage<DataAuthority.CommandResult> findExistingResult(DataAuthority.CommandEnvelope command) {
        return dataAPI.collection(COMMAND_KEYS_COLLECTION)
            .selectAsync(safeIdempotencyKey(command.idempotencyKey()))
            .thenApply(document -> {
                if (!document.exists()) {
                    return null;
                }
                UUID commandId = UUID.fromString(stringValue(document.get("commandId"), command.commandId().toString()));
                boolean accepted = booleanValue(document.get("accepted"), false);
                long revision = longValue(document.get("revision"), 0L);
                DataAuthority.RejectionReason rejectionReason = DataAuthority.RejectionReason.valueOf(
                    stringValue(document.get("rejectionReason"), DataAuthority.RejectionReason.NONE.name()));
                return new DataAuthority.CommandResult(
                    commandId,
                    accepted,
                    revision,
                    rejectionReason,
                    stringValue(document.get("message"), "")
                );
            });
    }

    private static boolean isExpired(DataAuthority.CommandEnvelope command) {
        return command.deadlineEpochMillis() > 0L && command.deadlineEpochMillis() < System.currentTimeMillis();
    }

    private static void copyPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    private static DataAuthority.CommandResult accepted(DataAuthority.CommandEnvelope command) {
        return new DataAuthority.CommandResult(command.commandId(), true, command.expectedRevision() + 1,
            DataAuthority.RejectionReason.NONE, "");
    }

    private static DataAuthority.CommandResult rejected(
        DataAuthority.CommandEnvelope command,
        DataAuthority.RejectionReason reason,
        String message
    ) {
        return new DataAuthority.CommandResult(command.commandId(), false, command.expectedRevision(), reason, message);
    }

    private static String safeIdempotencyKey(String idempotencyKey) {
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
