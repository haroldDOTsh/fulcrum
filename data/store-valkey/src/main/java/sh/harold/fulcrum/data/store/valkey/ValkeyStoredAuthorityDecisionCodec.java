package sh.harold.fulcrum.data.store.valkey;

import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

public interface ValkeyStoredAuthorityDecisionCodec<S, R> {
    String encode(StoredAuthorityDecision<S, R> decision);

    StoredAuthorityDecision<S, R> decode(String payload);
}
