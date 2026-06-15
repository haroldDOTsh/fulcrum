package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandScopeGuardTest {
    @Test
    void rejectsRankCommandWhoseScopeDoesNotMatchPlayer() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityCommandScopeGuard guard = new AuthorityCommandScopeGuard(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = guard.submit(rankCommand(
            playerId,
            "player:" + playerId
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE);
        assertThat(result.message()).contains("expected rank:player:" + playerId);
        assertThat(delegated).isFalse();
    }

    @Test
    void allowsMatchingRankCommandScope() {
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        AuthorityCommandScopeGuard guard = new AuthorityCommandScopeGuard(command -> {
            received.set(command);
            return CompletableFuture.completedFuture(accepted(command));
        });
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = guard.submit(rankCommand(
            playerId,
            "rank:player:" + playerId
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(received.get()).isInstanceOf(DataAuthority.PlayerRankCommand.class);
    }

    @Test
    void rejectsSessionCommandWhoseScopeDoesNotMatchPlayer() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityCommandScopeGuard guard = new AuthorityCommandScopeGuard(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });
        UUID playerId = UUID.randomUUID();

        DataAuthority.CommandResult result = guard.submit(sessionCommand(
            playerId,
            "rank:player:" + playerId
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE);
        assertThat(result.message()).contains("expected player:" + playerId);
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsMatchCommandWhoseScopeDoesNotMatchMatchId() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityCommandScopeGuard guard = new AuthorityCommandScopeGuard(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });
        UUID matchId = UUID.randomUUID();

        DataAuthority.CommandResult result = guard.submit(matchCommand(
            matchId,
            "match:" + UUID.randomUUID()
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE);
        assertThat(result.message()).contains("expected match:" + matchId);
        assertThat(delegated).isFalse();
    }

    private static DataAuthority.PlayerRankCommand rankCommand(UUID playerId, String scope) {
        UUID commandId = UUID.randomUUID();
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "GRANT_RANK",
                "rank-service",
                scope,
                "rank:" + commandId,
                System.currentTimeMillis() + 60_000L,
                "",
                DataAuthority.ANY_REVISION
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    private static DataAuthority.PlayerSessionCommand sessionCommand(UUID playerId, String scope) {
        UUID commandId = UUID.randomUUID();
        return new DataAuthority.PlayerSessionCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "START_SESSION",
                "session-service",
                scope,
                "session:" + commandId,
                System.currentTimeMillis() + 60_000L,
                "",
                DataAuthority.ANY_REVISION
            ),
            playerId,
            "ScopeUser",
            UUID.randomUUID(),
            System.currentTimeMillis(),
            "lobby-1",
            "proxy-1",
            "127.0.0.1",
            765,
            null
        );
    }

    private static DataAuthority.MatchCommand matchCommand(UUID matchId, String scope) {
        UUID commandId = UUID.randomUUID();
        return new DataAuthority.MatchCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "RECORD_MATCH_START",
                "match-service",
                scope,
                "match:" + commandId,
                System.currentTimeMillis() + 60_000L,
                "",
                DataAuthority.ANY_REVISION
            ),
            matchId,
            "duels",
            "arena-1",
            "server-1",
            "slot-1",
            "STARTED",
            System.currentTimeMillis(),
            null,
            Map.of("variant", "standard"),
            List.of()
        );
    }

    private static DataAuthority.CommandResult accepted(DataAuthority.AuthorityCommand command) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            true,
            1L,
            DataAuthority.RejectionReason.NONE,
            "accepted"
        );
    }
}
