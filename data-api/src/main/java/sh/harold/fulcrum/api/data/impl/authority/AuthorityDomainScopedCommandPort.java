package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Command-port decorator for an authority worker materialized for one declared domain.
 */
public final class AuthorityDomainScopedCommandPort implements DataAuthority.CommandPort {
    private final String domain;
    private final DataAuthority.CommandPort delegate;

    public AuthorityDomainScopedCommandPort(String domain, DataAuthority.CommandPort delegate) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        this.domain = domain;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        String commandDomain = DataAuthorityCommandContracts.contract(command.type()).domain();
        if (!domain.equals(commandDomain)) {
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "Authority worker for " + domain + " cannot apply " + commandDomain + " command "
                    + command.type()
            ));
        }
        return delegate.submit(command);
    }
}
