package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Rejects commands whose claimed actor attempts to impersonate a reserved authority principal.
 */
public final class AuthorityPrincipalCommandPort implements DataAuthority.CommandPort {
    public static final String KAFKA_COMMAND_LOG_PROVIDER = "kafka-command-log";

    private static final String MESSAGE_BUS_PROVIDER = "message-bus-provider";

    private final DataAuthority.CommandPort delegate;

    public AuthorityPrincipalCommandPort(DataAuthority.CommandPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        DataAuthority.CommandProvenance provenance = command.provenance();
        String verifiedPrincipal = provenance.verifiedPrincipal();
        if (transportProvider(provenance.providerKind())) {
            if (!AuthorityPrincipals.known(verifiedPrincipal)) {
                return CompletableFuture.completedFuture(rejected(
                    command,
                    "Authority command did not include a verified transport principal"
                ));
            }
            if (!verifiedPrincipal.equals(command.actorId())) {
                return CompletableFuture.completedFuture(rejected(
                    command,
                    "Actor " + command.actorId() + " must match verified transport principal " + verifiedPrincipal
                ));
            }
            return delegate.submit(command);
        }
        if (!AuthorityPrincipals.canClaimActor(verifiedPrincipal, command.actorId())) {
            return CompletableFuture.completedFuture(rejected(
                command,
                "Actor " + command.actorId() + " is reserved for verified principal " + verifiedPrincipal
            ));
        }
        return delegate.submit(command);
    }

    private static boolean transportProvider(String providerKind) {
        return MESSAGE_BUS_PROVIDER.equals(providerKind) || KAFKA_COMMAND_LOG_PROVIDER.equals(providerKind);
    }

    private static DataAuthority.CommandResult rejected(
        DataAuthority.AuthorityCommand command,
        String message
    ) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            false,
            command.expectedRevision(),
            DataAuthority.RejectionReason.INVALID_ACTOR,
            message
        );
    }
}
