package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.IdempotencyKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryIdempotencyLedger<S, R> implements IdempotencyLedger<S, R> {
    private final Map<IdempotencyKey, StoredAuthorityDecision<S, R>> decisions = new LinkedHashMap<>();

    @Override
    public Optional<StoredAuthorityDecision<S, R>> find(IdempotencyKey idempotencyKey) {
        return Optional.ofNullable(decisions.get(Objects.requireNonNull(idempotencyKey, "idempotencyKey")));
    }

    @Override
    public void store(IdempotencyKey idempotencyKey, String payloadFingerprint, AuthorityDecision<S, R> decision) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(decision, "decision");
        decisions.putIfAbsent(idempotencyKey, new StoredAuthorityDecision<>(payloadFingerprint, decision));
    }
}
