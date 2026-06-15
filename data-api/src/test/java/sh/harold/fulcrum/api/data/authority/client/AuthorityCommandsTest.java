package sh.harold.fulcrum.api.data.authority.client;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandsTest {
    @Test
    void subjectProvidesThinRoutableIdentityScope() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000010");

        DataAuthority.Subject subject = DataAuthority.Subject.player(playerId);

        assertThat(subject.subjectId()).isEqualTo(playerId);
        assertThat(subject.scope()).isEqualTo("subject:" + playerId);
    }

    @Test
    void rankCommandOwnsScopeIdempotencyAndExpectedRevision() {
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        DataAuthority.PlayerRankCommand command = AuthorityCommands.actor("rank-service")
            .rank(playerId)
            .grantRank("VIP", List.of("VIP", "DEFAULT"), 42L, 1_000L);

        AuthorityCommandManifest.validate(command);
        assertThat(command.declarationId()).isEqualTo("GRANT_RANK");
        assertThat(command.actorId()).isEqualTo("rank-service");
        assertThat(command.scope()).isEqualTo("rank:player:" + playerId);
        assertThat(command.idempotencyKey()).isEqualTo("GRANT_RANK:" + playerId + ":1000");
        assertThat(command.deadlineEpochMillis()).isEqualTo(6_000L);
        assertThat(command.expectedRevision()).isEqualTo(42L);
        assertThat(command.primaryRank()).isEqualTo("VIP");
        assertThat(command.ranks()).isEqualTo(List.of("VIP", "DEFAULT"));
    }

    @Test
    void playerProfileCommandsUsePlayerAggregateScope() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DataAuthority.PlayerProfileCommand login = AuthorityCommands.actor("paper-runtime")
            .player(playerId)
            .recordLogin("Richa", 2_000L, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                12, 0.5F, 20.0D, 18);
        DataAuthority.PlayerProfileCommand logout = AuthorityCommands.actor("paper-runtime")
            .player(playerId)
            .recordLogout("Richa", 3_000L, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                "lastJoin");

        AuthorityCommandManifest.validate(login);
        AuthorityCommandManifest.validate(logout);
        assertThat(login.subject()).isEqualTo(DataAuthority.Subject.player(playerId));
        assertThat(login.scope()).isEqualTo("player:" + playerId);
        assertThat(login.declarationId()).isEqualTo("RECORD_PLAYER_LOGIN");
        assertThat(login.level()).isEqualTo(12);
        assertThat(logout.scope()).isEqualTo("player:" + playerId);
        assertThat(logout.declarationId()).isEqualTo("RECORD_PLAYER_LOGOUT");
        assertThat(logout.playtimeStartField()).isEqualTo("lastJoin");
    }

    @Test
    void playerProfileCommandsDoNotExposePresenceRoutingFields() {
        assertThat(DataAuthority.PlayerProfileCommand.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("currentServer", "currentProxy");
    }

    @Test
    void sessionCommandsUseSubjectAggregateScope() {
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID sessionId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        DataAuthority.PlayerSessionCommand command = AuthorityCommands.actor("velocity-proxy")
            .session(playerId)
            .endSession("Richa", sessionId, 4_000L, "survival", "proxy-a", "127.0.0.1", 772, "quit");

        AuthorityCommandManifest.validate(command);
        assertThat(command.subject()).isEqualTo(DataAuthority.Subject.player(playerId));
        assertThat(command.scope()).isEqualTo("subject:" + playerId);
        assertThat(command.declarationId()).isEqualTo("END_SESSION");
        assertThat(command.disconnectReason()).isEqualTo("quit");
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

        AuthorityCommandManifest.validate(command);
        assertThat(command.scope()).isEqualTo("match:" + matchId);
        assertThat(command.idempotencyKey()).isEqualTo("RECORD_MATCH_END:" + matchId + ":1000");
        assertThat(command.deadlineEpochMillis()).isEqualTo(17_000L);
        assertThat(command.state()).isEqualTo("ENDED");
        assertThat(command.slotMetadata()).containsEntry("variant", "solo");
    }
}
