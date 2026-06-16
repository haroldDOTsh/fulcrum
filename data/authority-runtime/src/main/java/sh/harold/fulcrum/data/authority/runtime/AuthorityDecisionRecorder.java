package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityDecision;

@FunctionalInterface
public interface AuthorityDecisionRecorder<S, C extends CommandPayload, R> {
    void record(AuthorityCommandDelivery<C> delivery, AuthorityDecision<S, R> decision);
}
