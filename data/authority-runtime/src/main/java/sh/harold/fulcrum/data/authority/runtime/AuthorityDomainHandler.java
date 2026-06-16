package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;

@FunctionalInterface
public interface AuthorityDomainHandler<S, C extends CommandPayload, R> {
    AuthorityDecision<S, R> handle(AuthorityCommand<C> command, AuthorityRecord<S> currentRecord);
}
