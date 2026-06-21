package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryIdempotencyLedger<S, R> implements IdempotencyLedger<S, R> {
    private final Clock clock;
    private final Optional<Duration> ttl;
    private final Map<IdempotencyKey, Entry<S, R>> entries = new LinkedHashMap<>();

    public InMemoryIdempotencyLedger() {
        this(Clock.systemUTC(), Optional.empty());
    }

    public InMemoryIdempotencyLedger(Clock clock, Optional<Duration> ttl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = ttl == null ? Optional.empty() : ttl;
        this.ttl.ifPresent(duration -> {
            if (duration.isNegative() || duration.isZero() || duration.toMillis() <= 0) {
                throw new IllegalArgumentException("ttl must be positive when present");
            }
        });
    }

    @Override
    public synchronized Optional<StoredAuthorityDecision<S, R>> find(IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Entry<S, R> entry = entries.get(idempotencyKey);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isPresent() && !entry.expiresAt().orElseThrow().isAfter(clock.instant())) {
            entries.remove(idempotencyKey);
            return Optional.empty();
        }
        return Optional.of(entry.stored());
    }

    @Override
    public synchronized void store(
            IdempotencyKey idempotencyKey,
            String payloadFingerprint,
            AuthorityDecision<S, R> decision) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(decision, "decision");
        if (find(idempotencyKey).isPresent()) {
            return;
        }
        Optional<Instant> expiresAt = ttl.map(duration -> clock.instant().plus(duration));
        entries.put(idempotencyKey, new Entry<>(new StoredAuthorityDecision<>(payloadFingerprint, decision), expiresAt));
    }

    public synchronized int size() {
        return entries.size();
    }

    private record Entry<S, R>(
            StoredAuthorityDecision<S, R> stored,
            Optional<Instant> expiresAt) {
        private Entry {
            stored = Objects.requireNonNull(stored, "stored");
            expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
        }
    }
}
