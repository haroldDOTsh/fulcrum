package sh.harold.fulcrum.data.store.postgresql;

import sh.harold.fulcrum.data.authority.AuthorityDecision;

@FunctionalInterface
public interface JdbcAuthorityDecisionPayloadEncoder<S, R> {
    String encode(AuthorityDecision<S, R> decision);
}
