package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.IdempotencyKey;

import java.util.Optional;

public interface IdempotencyLedger<S, R> {
    Optional<StoredAuthorityDecision<S, R>> find(IdempotencyKey idempotencyKey);

    void store(IdempotencyKey idempotencyKey, String payloadFingerprint, AuthorityDecision<S, R> decision);
}
