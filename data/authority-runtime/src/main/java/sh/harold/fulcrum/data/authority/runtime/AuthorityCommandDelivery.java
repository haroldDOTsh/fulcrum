package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;

import java.util.Objects;

public record AuthorityCommandDelivery<C extends CommandPayload>(
        AuthorityCommand<C> command,
        AuthorityOffset offset) {
    public AuthorityCommandDelivery {
        command = Objects.requireNonNull(command, "command");
        offset = Objects.requireNonNull(offset, "offset");
    }
}
