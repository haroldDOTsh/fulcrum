package sh.harold.fulcrum.data.store.valkey;

import io.valkey.UnifiedJedis;
import io.valkey.params.SetParams;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class ValkeyIdempotencyLedger<S, R> implements IdempotencyLedger<S, R> {
    private final UnifiedJedis client;
    private final String keyPrefix;
    private final ValkeyStoredAuthorityDecisionCodec<S, R> codec;
    private final Optional<Duration> ttl;

    public ValkeyIdempotencyLedger(
            UnifiedJedis client,
            String keyPrefix,
            ValkeyStoredAuthorityDecisionCodec<S, R> codec) {
        this(client, keyPrefix, codec, Optional.empty());
    }

    public ValkeyIdempotencyLedger(
            UnifiedJedis client,
            String keyPrefix,
            ValkeyStoredAuthorityDecisionCodec<S, R> codec,
            Optional<Duration> ttl) {
        this.client = Objects.requireNonNull(client, "client");
        this.keyPrefix = requireNonBlank(keyPrefix, "keyPrefix");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.ttl = ttl == null ? Optional.empty() : ttl;
        this.ttl.ifPresent(duration -> {
            if (duration.isNegative() || duration.isZero() || duration.toMillis() <= 0) {
                throw new IllegalArgumentException("ttl must be positive when present");
            }
        });
    }

    @Override
    public Optional<StoredAuthorityDecision<S, R>> find(IdempotencyKey idempotencyKey) {
        String payload = client.get(key(idempotencyKey));
        return payload == null ? Optional.empty() : Optional.of(codec.decode(payload));
    }

    @Override
    public void store(IdempotencyKey idempotencyKey, String payloadFingerprint, AuthorityDecision<S, R> decision) {
        Objects.requireNonNull(decision, "decision");
        StoredAuthorityDecision<S, R> stored = new StoredAuthorityDecision<>(payloadFingerprint, decision);
        SetParams params = SetParams.setParams().nx();
        ttl.ifPresent(duration -> params.px(duration.toMillis()));
        client.set(key(idempotencyKey), codec.encode(stored), params);
    }

    public String key(IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return keyPrefix + ":" + idempotencyKey.value();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
