package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
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
        AuthorityCommandManifest.CommandContract contract =
            AuthorityCommandManifest.declaration(command.declarationId());
        Object aggregateId = AuthorityCommandPayloads.payload(command).get(contract.aggregateIdField());
        if (aggregateId == null || aggregateId.toString().isBlank()) {
            throw new IllegalArgumentException(
                "Command " + command.declarationId()
                    + " is missing aggregate id field " + contract.aggregateIdField()
            );
        }
        return contract.aggregateScopePrefix() + aggregateId;
    }
}
