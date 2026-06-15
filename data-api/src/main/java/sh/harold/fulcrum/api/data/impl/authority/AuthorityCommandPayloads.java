package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AuthorityCommandPayloads {
    private AuthorityCommandPayloads() {
    }

    static Map<String, Object> payload(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof DataAuthority.PlayerProfileCommand profile) {
            return new MapBuilder()
                .put("playerId", profile.playerId().toString())
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
        if (command instanceof DataAuthority.PlayerSessionCommand session) {
            MapBuilder payload = new MapBuilder()
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
        if (command instanceof DataAuthority.PlayerRankCommand rank) {
            return new MapBuilder()
                .put("playerId", rank.playerId().toString())
                .put("primaryRank", rank.primaryRank())
                .put("ranks", rank.ranks())
                .build();
        }
        if (command instanceof DataAuthority.MatchCommand match) {
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
        throw new IllegalArgumentException("Unsupported authority command type: " + command.getClass().getName());
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
