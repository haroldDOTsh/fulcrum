package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.CommandPayload;

@FunctionalInterface
public interface AuthorityMutation<S, C extends CommandPayload, R> {
    AuthorityMutationResult<S, R> apply(AuthorityCommand<C> command, AuthorityRecord<S> currentRecord);
}
