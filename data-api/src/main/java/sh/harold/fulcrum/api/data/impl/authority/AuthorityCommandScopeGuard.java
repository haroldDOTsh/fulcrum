package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Rejects commands whose manifest scope does not match the typed aggregate payload.
 */
public final class AuthorityCommandScopeGuard implements DataAuthority.CommandPort {
    private final DataAuthority.CommandPort delegate;

    public AuthorityCommandScopeGuard(DataAuthority.CommandPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        String expectedScope = expectedScope(command);
        if (!expectedScope.equals(command.scope())) {
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.INVALID_SCOPE,
                "Command scope mismatch for " + command.declarationId()
                    + ": expected " + expectedScope + " but was " + command.scope()
            ));
        }
        return delegate.submit(command);
    }

    private static String expectedScope(DataAuthority.AuthorityCommand command) {
        if (command instanceof DataAuthority.PlayerRankCommand rank) {
            return rankScope(rank.playerId());
        }
        if (command instanceof DataAuthority.PlayerProfileCommand profile) {
            return playerScope(profile.playerId());
        }
        if (command instanceof DataAuthority.PlayerSessionCommand session) {
            return playerScope(session.playerId());
        }
        if (command instanceof DataAuthority.MatchCommand match) {
            return matchScope(match.matchId());
        }
        throw new IllegalArgumentException("Unsupported authority command type: " + command.getClass().getName());
    }

    private static String playerScope(UUID playerId) {
        return "player:" + playerId;
    }

    private static String rankScope(UUID playerId) {
        return "rank:player:" + playerId;
    }

    private static String matchScope(UUID matchId) {
        return "match:" + matchId;
    }
}
