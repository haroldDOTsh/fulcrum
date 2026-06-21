package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryAuthorityEmissionSink implements AuthorityEmissionSink {
    private final List<AuthorityEmission> emissions = new ArrayList<>();
    private final Map<AuthorityEmissionKind, Map<String, String>> latestByKindAndKey =
            new EnumMap<>(AuthorityEmissionKind.class);

    @Override
    public synchronized void publish(AuthorityEmission emission) {
        Objects.requireNonNull(emission, "emission");
        emissions.add(emission);
        latestByKindAndKey
                .computeIfAbsent(emission.kind(), ignored -> new java.util.LinkedHashMap<>())
                .put(emission.key(), emission.payload());
    }

    public synchronized List<AuthorityEmission> emissions() {
        return List.copyOf(emissions);
    }

    public synchronized List<String> payloads(AuthorityEmissionKind kind) {
        return emissions.stream()
                .filter(emission -> emission.kind() == kind)
                .map(AuthorityEmission::payload)
                .toList();
    }

    public synchronized String latestPayload(AuthorityEmissionKind kind, String key) {
        Map<String, String> byKey = latestByKindAndKey.get(kind);
        return byKey == null ? null : byKey.get(key);
    }
}
