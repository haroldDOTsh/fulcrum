package sh.harold.fulcrum.data.store.valkey;

import io.valkey.UnifiedJedis;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class ValkeyAuthorityCacheSink implements AuthorityEmissionSink {
    private final UnifiedJedis client;
    private final Optional<Duration> ttl;

    public ValkeyAuthorityCacheSink(UnifiedJedis client) {
        this(client, Optional.empty());
    }

    public ValkeyAuthorityCacheSink(UnifiedJedis client, Optional<Duration> ttl) {
        this.client = Objects.requireNonNull(client, "client");
        this.ttl = ttl == null ? Optional.empty() : ttl;
        this.ttl.ifPresent(duration -> {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("ttl must be positive when present");
            }
        });
    }

    @Override
    public void publish(AuthorityEmission emission) {
        if (emission.kind() != AuthorityEmissionKind.CACHE_WRITE) {
            return;
        }
        client.set(emission.key(), emission.payload());
        ttl.ifPresent(duration -> client.expire(emission.key(), duration.toSeconds()));
    }
}
