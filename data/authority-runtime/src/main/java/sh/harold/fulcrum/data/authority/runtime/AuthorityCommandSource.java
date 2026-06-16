package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.CommandPayload;

import java.util.Optional;

@FunctionalInterface
public interface AuthorityCommandSource<C extends CommandPayload> {
    Optional<AuthorityCommandDelivery<C>> poll();
}
