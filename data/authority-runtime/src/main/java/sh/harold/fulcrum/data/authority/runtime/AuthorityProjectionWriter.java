package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;

@FunctionalInterface
public interface AuthorityProjectionWriter<S, C extends CommandPayload, R> {
    void write(AuthorityCommand<C> command, AuthorityDecision<S, R> decision);
}
