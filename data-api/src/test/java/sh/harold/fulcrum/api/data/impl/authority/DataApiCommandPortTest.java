package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.json.JsonConnectionAdapter;

import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataApiCommandPortTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsPlayerLifecycleThroughCommands() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult login = port.submit(command(
            DataAuthority.CommandType.START_SESSION,
            playerId,
            Map.of(
                "username", "Notch",
                "timestamp", 1000L,
                "lastProxySession", 1000L,
                "protocolVersion", 765,
                "currentServer", "lobby-1"
            )
        )).toCompletableFuture().join();

        assertThat(login.accepted()).isTrue();

        DataAuthority.CommandResult switchServer = port.submit(command(
            DataAuthority.CommandType.RENEW_SESSION,
            playerId,
            Map.of(
                "timestamp", 2000L,
                "currentServer", "bedwars-1",
                "lastServerSwitch", 2000L
            )
        )).toCompletableFuture().join();

        assertThat(switchServer.accepted()).isTrue();

        DataAuthority.CommandResult logout = port.submit(command(
            DataAuthority.CommandType.END_SESSION,
            playerId,
            Map.of(
                "timestamp", 4000L,
                "playtimeStartField", "lastProxySession",
                "clearCurrentServer", true
            )
        )).toCompletableFuture().join();

        assertThat(logout.accepted()).isTrue();

        Document document = dataAPI.collection("players").document(playerId.toString());
        assertThat(document.get("username")).isEqualTo("Notch");
        assertThat(document.get("joinCount")).isEqualTo(1);
        assertThat(document.get("currentServer")).isNull();
        assertThat(document.get("totalPlaytime")).isEqualTo(3000L);

        Document audit = dataAPI.collection("authority_commands").document(login.commandId().toString());
        assertThat(audit.exists()).isTrue();
        assertThat(audit.get("type")).isEqualTo(DataAuthority.CommandType.START_SESSION.name());
        assertThat(audit.get("accepted")).isEqualTo(true);
    }

    @Test
    void rejectsInvalidPlayerScope() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);

        DataAuthority.CommandEnvelope command = new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            DataAuthority.CommandType.START_SESSION,
            "test",
            "server:missing",
            "bad-scope",
            System.currentTimeMillis() + 5000L,
            "",
            0L,
            Map.of("timestamp", 1000L)
        );

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE);

        Document audit = dataAPI.collection("authority_commands").document(command.commandId().toString());
        assertThat(audit.exists()).isTrue();
        assertThat(audit.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE.name());
    }

    @Test
    void duplicateIdempotencyKeyReturnsFirstResultWithoutSecondMutation() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandEnvelope first = command(
            DataAuthority.CommandType.START_SESSION,
            playerId,
            Map.of(
                "username", "Notch",
                "timestamp", 1000L,
                "lastProxySession", 1000L
            )
        );
        DataAuthority.CommandEnvelope duplicate = command(
            DataAuthority.CommandType.START_SESSION,
            playerId,
            Map.of(
                "username", "Steve",
                "timestamp", 1000L,
                "lastProxySession", 1000L
            )
        );

        DataAuthority.CommandResult firstResult = port.submit(first).toCompletableFuture().join();
        DataAuthority.CommandResult duplicateResult = port.submit(duplicate).toCompletableFuture().join();

        assertThat(duplicateResult.commandId()).isEqualTo(firstResult.commandId());

        Document document = dataAPI.collection("players").document(playerId.toString());
        assertThat(document.get("username")).isEqualTo("Notch");
        assertThat(document.get("joinCount")).isEqualTo(1);
    }

    @Test
    void expiredCommandIsRejectedBeforeMutation() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        DataAuthority.CommandEnvelope expired = new DataAuthority.CommandEnvelope(
            commandId,
            DataAuthority.CommandType.START_SESSION,
            "test",
            "player:" + playerId,
            "expired:" + commandId,
            1L,
            "",
            0L,
            Map.of("playerId", playerId.toString(), "username", "Notch", "timestamp", 1000L)
        );

        DataAuthority.CommandResult result = port.submit(expired).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.EXPIRED_DEADLINE);
        assertThat(dataAPI.collection("players").document(playerId.toString()).exists()).isFalse();
        assertThat(dataAPI.collection("authority_commands").document(commandId.toString()).exists()).isTrue();
    }

    @Test
    void persistsRankProjectionThroughCommand() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = port.submit(command(
            DataAuthority.CommandType.GRANT_RANK,
            playerId,
            Map.of(
                "primaryRank", "ADMIN",
                "ranks", List.of("DEFAULT", "ADMIN")
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();

        Document rankDoc = dataAPI.collection("player_ranks").document(playerId.toString());
        assertThat(rankDoc.get("primary_rank")).isEqualTo("ADMIN");
        assertThat(rankDoc.get("ranks")).isEqualTo(List.of("DEFAULT", "ADMIN"));
    }

    @Test
    void persistsMatchLifecycleThroughCommands() {
        DataAPI dataAPI = DataAPI.create(new JsonConnectionAdapter(tempDir));
        DataApiCommandPort port = new DataApiCommandPort(dataAPI);
        UUID matchId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult start = port.submit(matchCommand(
            DataAuthority.CommandType.RECORD_MATCH_START,
            matchId,
            Map.of(
                "familyId", "bedwars",
                "slotId", "mini-1-slot-1",
                "serverId", "mini-1",
                "startedAt", 1000L
            )
        )).toCompletableFuture().join();

        assertThat(start.accepted()).isTrue();

        DataAuthority.CommandResult end = port.submit(matchCommand(
            DataAuthority.CommandType.RECORD_MATCH_END,
            matchId,
            Map.of(
                "familyId", "bedwars",
                "slotId", "mini-1-slot-1",
                "serverId", "mini-1",
                "startedAt", 1000L,
                "endedAt", 4000L,
                "participants", List.of(Map.of(
                    "playerId", playerId.toString(),
                    "state", "ELIMINATED",
                    "stats", Map.of("kills", 2)
                ))
            )
        )).toCompletableFuture().join();

        assertThat(end.accepted()).isTrue();

        Document match = dataAPI.collection("match_records").document(matchId.toString());
        assertThat(match.get("familyId")).isEqualTo("bedwars");
        assertThat(match.get("state")).isEqualTo("ENDED");
        assertThat(match.get("endedAt")).isEqualTo(4000L);

        Document participant = dataAPI.collection("match_participant_stats")
            .document(matchId + "_" + playerId);
        assertThat(participant.get("playerId")).isEqualTo(playerId.toString());
        assertThat(participant.get("state")).isEqualTo("ELIMINATED");
    }

    private static DataAuthority.CommandEnvelope command(
        DataAuthority.CommandType type,
        UUID playerId,
        Map<String, Object> payload
    ) {
        Map<String, Object> fullPayload = new HashMap<>(payload);
        fullPayload.put("playerId", playerId.toString());
        return new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            type,
            "test",
            "player:" + playerId,
            type.name() + ":" + playerId + ":" + fullPayload.getOrDefault("timestamp", 0L),
            System.currentTimeMillis() + 5000L,
            "",
            0L,
            fullPayload
        );
    }

    private static DataAuthority.CommandEnvelope matchCommand(
        DataAuthority.CommandType type,
        UUID matchId,
        Map<String, Object> payload
    ) {
        Map<String, Object> fullPayload = new HashMap<>(payload);
        fullPayload.put("matchId", matchId.toString());
        return new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            type,
            "test",
            "match:" + matchId,
            type.name() + ":" + matchId + ":" + fullPayload.getOrDefault("startedAt", 0L)
                + ":" + fullPayload.getOrDefault("endedAt", 0L),
            System.currentTimeMillis() + 5000L,
            "",
            0L,
            fullPayload
        );
    }
}
