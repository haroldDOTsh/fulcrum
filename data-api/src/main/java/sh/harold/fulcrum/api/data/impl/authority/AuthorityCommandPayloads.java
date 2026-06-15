package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class AuthorityCommandPayloads {
    private AuthorityCommandPayloads() {
    }

    static Map<String, Object> payload(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        return AuthorityDomainDeclarations.command(command.declarationId()).payload(command);
    }

    static Map<String, Object> profilePayload(DataAuthority.AuthorityCommand command) {
        DataAuthority.PlayerProfileCommand profile = (DataAuthority.PlayerProfileCommand) command;
        return new MapBuilder()
            .put("playerId", profile.playerId().toString())
            .put("subjectId", profile.subject().subjectId().toString())
            .put("username", profile.username())
            .put("timestamp", profile.timestampEpochMillis())
            .put("online", "RECORD_PLAYER_LOGIN".equals(profile.declarationId()))
            .put("currentServer", profile.currentServer())
            .put("currentProxy", profile.currentProxy())
            .put("lastIp", profile.lastIp())
            .put("lastWorld", profile.lastWorld())
            .put("lastLocation", profile.lastLocation())
            .put("gamemode", profile.gameMode())
            .put("level", profile.level())
            .put("exp", profile.exp())
            .put("health", profile.health())
            .put("foodLevel", profile.foodLevel())
            .put("playtimeStartField", profile.playtimeStartField())
            .build();
    }

    static DataAuthority.AuthorityCommand profileCommand(
        DataAuthority.CommandManifest manifest,
        Map<String, Object> payload
    ) {
        return new DataAuthority.PlayerProfileCommand(
            manifest,
            uuid(payload.get("playerId")),
            string(payload.get("username")),
            longValue(payload.get("timestamp"), System.currentTimeMillis()),
            string(payload.get("currentServer")),
            string(payload.get("currentProxy")),
            string(payload.get("lastIp")),
            string(payload.get("lastWorld")),
            string(payload.get("lastLocation")),
            string(payload.get("gamemode")),
            nullableInt(payload.get("level")),
            nullableFloat(payload.get("exp")),
            nullableDouble(payload.get("health")),
            nullableInt(payload.get("foodLevel")),
            string(payload.get("playtimeStartField"))
        );
    }

    static Map<String, Object> sessionPayload(DataAuthority.AuthorityCommand command) {
        DataAuthority.PlayerSessionCommand session = (DataAuthority.PlayerSessionCommand) command;
        MapBuilder payload = new MapBuilder()
            .put("subjectId", session.subject().subjectId().toString())
            .put("playerId", session.playerId().toString())
            .put("username", session.username())
            .put("sessionId", session.sessionId() == null ? null : session.sessionId().toString())
            .put("timestamp", session.timestampEpochMillis())
            .put("online", !"END_SESSION".equals(session.declarationId()))
            .put("currentServer", session.currentServer())
            .put("currentProxy", session.currentProxy())
            .put("lastIp", session.lastIp())
            .put("protocolVersion", session.protocolVersion())
            .put("disconnectReason", session.disconnectReason());
        if ("START_SESSION".equals(session.declarationId())) {
            payload.put("lastProxySession", session.timestampEpochMillis());
        }
        if ("RENEW_SESSION".equals(session.declarationId())) {
            payload.put("lastServerSwitch", session.timestampEpochMillis());
        }
        if ("END_SESSION".equals(session.declarationId())) {
            payload.put("playtimeStartField", "lastProxySession");
            payload.put("clearCurrentServer", true);
        }
        return payload.build();
    }

    static DataAuthority.AuthorityCommand sessionCommand(
        DataAuthority.CommandManifest manifest,
        Map<String, Object> payload
    ) {
        return new DataAuthority.PlayerSessionCommand(
            manifest,
            uuid(payload.getOrDefault("playerId", payload.get("subjectId"))),
            string(payload.get("username")),
            nullableUuid(payload.get("sessionId")),
            longValue(payload.get("timestamp"), System.currentTimeMillis()),
            string(payload.get("currentServer")),
            string(payload.get("currentProxy")),
            string(payload.get("lastIp")),
            nullableInt(payload.get("protocolVersion")),
            string(payload.get("disconnectReason"))
        );
    }

    static Map<String, Object> rankPayload(DataAuthority.AuthorityCommand command) {
        DataAuthority.PlayerRankCommand rank = (DataAuthority.PlayerRankCommand) command;
        return new MapBuilder()
            .put("playerId", rank.playerId().toString())
            .put("primaryRank", rank.primaryRank())
            .put("ranks", rank.ranks())
            .build();
    }

    static DataAuthority.AuthorityCommand rankCommand(
        DataAuthority.CommandManifest manifest,
        Map<String, Object> payload
    ) {
        return new DataAuthority.PlayerRankCommand(
            manifest,
            uuid(payload.get("playerId")),
            string(payload.get("primaryRank")),
            stringList(payload.get("ranks"))
        );
    }

    static Map<String, Object> matchPayload(DataAuthority.AuthorityCommand command) {
        DataAuthority.MatchCommand match = (DataAuthority.MatchCommand) command;
        MapBuilder payload = new MapBuilder()
            .put("matchId", match.matchId().toString())
            .put("familyId", match.familyId())
            .put("mapId", match.mapId())
            .put("serverId", match.serverId())
            .put("slotId", match.slotId())
            .put("state", match.state())
            .put("startedAt", match.startedAtEpochMillis())
            .put("endedAt", match.endedAtEpochMillis())
            .put("slotMetadata", match.slotMetadata());
        for (String key : List.of("variant", "targetWorld")) {
            if (match.slotMetadata().containsKey(key)) {
                payload.put(key, match.slotMetadata().get(key));
            }
        }
        payload.put("participants", match.participants().stream()
            .map(AuthorityCommandPayloads::participantPayload)
            .toList());
        return payload.build();
    }

    static DataAuthority.AuthorityCommand matchCommand(
        DataAuthority.CommandManifest manifest,
        Map<String, Object> payload
    ) {
        return new DataAuthority.MatchCommand(
            manifest,
            uuid(payload.get("matchId")),
            string(payload.get("familyId")),
            string(payload.get("mapId")),
            string(payload.get("serverId")),
            string(payload.get("slotId")),
            string(payload.get("state")),
            nullableLong(payload.get("startedAt")),
            nullableLong(payload.get("endedAt")),
            stringObjectMap(mapValue(payload.get("slotMetadata"))),
            participants(payload.get("participants"))
        );
    }

    private static Map<String, Object> participantPayload(DataAuthority.MatchParticipant participant) {
        return new MapBuilder()
            .put("playerId", participant.playerId().toString())
            .put("teamId", participant.teamId())
            .put("placement", participant.placement())
            .put("state", participant.state())
            .put("stats", participant.stats())
            .build();
    }

    private static List<DataAuthority.MatchParticipant> participants(Object rawParticipants) {
        if (!(rawParticipants instanceof Iterable<?> rawValues)) {
            return List.of();
        }
        List<DataAuthority.MatchParticipant> participants = new ArrayList<>();
        for (Object raw : rawValues) {
            Map<String, Object> values = stringObjectMap(mapValue(raw));
            Object rawPlayerId = values.get("playerId");
            if (rawPlayerId == null) {
                continue;
            }
            participants.add(new DataAuthority.MatchParticipant(
                uuid(rawPlayerId),
                string(values.get("teamId")),
                nullableInt(values.get("placement")),
                string(values.get("state")),
                stringObjectMap(mapValue(values.get("stats")))
            ));
        }
        return participants;
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(key.toString(), value);
            }
        });
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("UUID value is required");
        }
        return UUID.fromString(value.toString());
    }

    private static UUID nullableUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : uuid(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : longValue(value, 0L);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : intValue(value, 0);
    }

    private static Float nullableFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return value == null ? null : Float.parseFloat(value.toString());
    }

    private static Double nullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? null : Double.parseDouble(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class MapBuilder {
        private final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();

        private MapBuilder put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }
}
