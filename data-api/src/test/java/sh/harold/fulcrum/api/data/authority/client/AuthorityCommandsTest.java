package sh.harold.fulcrum.api.data.authority.client;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandsTest {
    @Test
    void rankCommandOwnsScopeIdempotencyAndExpectedRevision() {
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        DataAuthority.PlayerRankCommand command = AuthorityCommands.actor("rank-service")
            .rank(playerId)
            .grantRank("VIP", List.of("VIP", "DEFAULT"), 42L, 1_000L);

        DataAuthorityCommandContracts.validate(command);
        assertThat(command.type()).isEqualTo(DataAuthority.CommandType.GRANT_RANK);
        assertThat(command.actorId()).isEqualTo("rank-service");
        assertThat(command.scope()).isEqualTo("rank:player:" + playerId);
        assertThat(command.idempotencyKey()).isEqualTo("GRANT_RANK:" + playerId + ":1000");
        assertThat(command.deadlineEpochMillis()).isEqualTo(6_000L);
        assertThat(command.expectedRevision()).isEqualTo(42L);
        assertThat(command.payload()).containsEntry("primaryRank", "VIP")
            .containsEntry("ranks", List.of("VIP", "DEFAULT"));
    }

    @Test
    void playerProfileCommandsUsePlayerAggregateScope() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DataAuthority.PlayerProfileCommand login = AuthorityCommands.actor("paper-runtime")
            .player(playerId)
            .recordLogin("Richa", 2_000L, null, null, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                12, 0.5F, 20.0D, 18);
        DataAuthority.PlayerProfileCommand logout = AuthorityCommands.actor("paper-runtime")
            .player(playerId)
            .recordLogout("Richa", 3_000L, null, null, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                "lastJoin");

        DataAuthorityCommandContracts.validate(login);
        DataAuthorityCommandContracts.validate(logout);
        assertThat(login.scope()).isEqualTo("player:" + playerId);
        assertThat(login.payload()).containsEntry("online", true)
            .containsEntry("level", 12);
        assertThat(logout.scope()).isEqualTo("player:" + playerId);
        assertThat(logout.payload()).containsEntry("online", false)
            .containsEntry("playtimeStartField", "lastJoin");
    }

    @Test
    void sessionCommandsUsePlayerAggregateScope() {
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID sessionId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        DataAuthority.PlayerSessionCommand command = AuthorityCommands.actor("velocity-proxy")
            .session(playerId)
            .endSession("Richa", sessionId, 4_000L, "survival", "proxy-a", "127.0.0.1", 772, "quit");

        DataAuthorityCommandContracts.validate(command);
        assertThat(command.scope()).isEqualTo("player:" + playerId);
        assertThat(command.payload()).containsEntry("online", false)
            .containsEntry("clearCurrentServer", true)
            .containsEntry("disconnectReason", "quit");
    }

    @Test
    void matchCommandsKeepIdempotencyTimestampSeparateFromDeadlineTimestamp() {
        UUID matchId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        DataAuthority.MatchCommand command = AuthorityCommands.actor("arena-01")
            .match(matchId)
            .recordEnd(
                "duels",
                "map-a",
                "arena-01",
                "slot-1",
                "ENDED",
                1_000L,
                10_000L,
                Map.of("variant", "solo"),
                List.of(),
                12_000L
            );

        DataAuthorityCommandContracts.validate(command);
        assertThat(command.scope()).isEqualTo("match:" + matchId);
        assertThat(command.idempotencyKey()).isEqualTo("RECORD_MATCH_END:" + matchId + ":1000");
        assertThat(command.deadlineEpochMillis()).isEqualTo(17_000L);
        assertThat(command.payload()).containsEntry("state", "ENDED")
            .containsEntry("variant", "solo");
    }
}
