package sh.harold.fulcrum.api.data.authority.client;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandsTest {
    private static final String TRANSPORT_ACTOR = "node:transport-unverified";

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

        DataAuthority.AuthorityCommand command = AuthorityCommands.transport()
            .rank(playerId)
            .grantRank("VIP", List.of("VIP", "DEFAULT"), 42L, 1_000L);

        AuthorityCommandManifest.validate(command);
        assertThat(command.declarationId()).isEqualTo("GRANT_RANK");
        assertThat(command.actorId()).isEqualTo(TRANSPORT_ACTOR);
        assertThat(command.scope()).isEqualTo("rank:player:" + playerId);
        assertThat(command.idempotencyKey()).isEqualTo("GRANT_RANK:" + playerId + ":1000");
        assertThat(command.deadlineEpochMillis()).isEqualTo(6_000L);
        assertThat(command.expectedRevision()).isEqualTo(42L);
        assertThat(command).isInstanceOfSatisfying(DataAuthority.PlayerRankCommand.class, rank -> {
            assertThat(rank.primaryRank()).isEqualTo("VIP");
            assertThat(rank.ranks()).isEqualTo(List.of("VIP", "DEFAULT"));
        });
    }

    @Test
    void playerProfileCommandsUsePlayerAggregateScope() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DataAuthority.AuthorityCommand login = AuthorityCommands.transport()
            .player(playerId)
            .recordLogin("Richa", 2_000L, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                12, 0.5F, 20.0D, 18);
        DataAuthority.AuthorityCommand logout = AuthorityCommands.transport()
            .player(playerId)
            .recordLogout("Richa", 3_000L, "127.0.0.1", "world", "1,64,1", "SURVIVAL",
                "lastJoin");

        AuthorityCommandManifest.validate(login);
        AuthorityCommandManifest.validate(logout);
        assertThat(login.scope()).isEqualTo("player:" + playerId);
        assertThat(login.declarationId()).isEqualTo("RECORD_PLAYER_LOGIN");
        assertThat(logout.scope()).isEqualTo("player:" + playerId);
        assertThat(logout.declarationId()).isEqualTo("RECORD_PLAYER_LOGOUT");
        assertThat(login).isInstanceOfSatisfying(DataAuthority.PlayerProfileCommand.class, profile -> {
            assertThat(profile.subject()).isEqualTo(DataAuthority.Subject.player(playerId));
            assertThat(profile.level()).isEqualTo(12);
        });
        assertThat(logout).isInstanceOfSatisfying(DataAuthority.PlayerProfileCommand.class, profile ->
            assertThat(profile.playtimeStartField()).isEqualTo("lastJoin")
        );
    }

    @Test
    void playerProfileCommandsDoNotExposePresenceRoutingFields() {
        assertThat(DataAuthority.PlayerProfileCommand.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("currentServer", "currentProxy");
    }

    @Test
    void commandClientLeafMethodsExposeAuthorityCommandOnly() {
        assertThat(returnTypes(AuthorityCommands.PlayerCommands.class, "recordLogin", "recordLogout"))
            .containsOnly(DataAuthority.AuthorityCommand.class);
        assertThat(returnTypes(AuthorityCommands.SessionCommands.class, "startSession", "renewSession", "endSession"))
            .containsOnly(DataAuthority.AuthorityCommand.class);
        assertThat(returnTypes(AuthorityCommands.RankCommands.class, "grantRank", "revokeRank"))
            .containsOnly(DataAuthority.AuthorityCommand.class);
        assertThat(returnTypes(AuthorityCommands.MatchCommands.class, "recordStart", "recordEnd"))
            .containsOnly(DataAuthority.AuthorityCommand.class);
    }

    @Test
    void sessionCommandsUseSubjectAggregateScope() {
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID sessionId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        DataAuthority.AuthorityCommand command = AuthorityCommands.transport()
            .session(playerId)
            .endSession("Richa", sessionId, 4_000L, "survival", "proxy-a", "127.0.0.1", 772, "quit");

        AuthorityCommandManifest.validate(command);
        assertThat(command.scope()).isEqualTo("subject:" + playerId);
        assertThat(command.declarationId()).isEqualTo("END_SESSION");
        assertThat(command).isInstanceOfSatisfying(DataAuthority.PlayerSessionCommand.class, session -> {
            assertThat(session.subject()).isEqualTo(DataAuthority.Subject.player(playerId));
            assertThat(session.disconnectReason()).isEqualTo("quit");
        });
    }

    @Test
    void matchCommandsKeepIdempotencyTimestampSeparateFromDeadlineTimestamp() {
        UUID matchId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        DataAuthority.AuthorityCommand command = AuthorityCommands.transport()
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
        assertThat(command).isInstanceOfSatisfying(DataAuthority.MatchCommand.class, match -> {
            assertThat(match.state()).isEqualTo("ENDED");
            assertThat(match.slotMetadata()).containsEntry("variant", "solo");
        });
    }

    @Test
    void commandClientDoesNotExposeFreeStringActorFactory() {
        assertThat(AuthorityCommands.class.getDeclaredMethods())
            .filteredOn(method -> method.getName().equals("actor"))
            .noneMatch(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class);
    }

    private static List<Class<?>> returnTypes(Class<?> owner, String... methodNames) {
        Set<String> expectedNames = Set.of(methodNames);
        return Arrays.stream(owner.getDeclaredMethods())
            .filter(method -> expectedNames.contains(method.getName()))
            .map(Method::getReturnType)
            .toList();
    }
}
