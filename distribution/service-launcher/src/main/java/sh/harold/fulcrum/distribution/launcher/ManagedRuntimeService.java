package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Instant;
import java.util.Objects;

final class ManagedRuntimeService implements AutoCloseable {
    private final LaunchEntry entry;
    private final HostSecurityContext securityContext;
    private final RuntimeServiceEngine engine;
    private Instant startedAt;

    ManagedRuntimeService(
            LaunchEntry entry,
            HostSecurityContext securityContext,
            RuntimeConnectionSettings connectionSettings,
            RuntimeExternalClients externalClients) {
        this(entry, securityContext, RuntimeServiceEngines.create(
                entry,
                securityContext,
                connectionSettings,
                externalClients));
    }

    ManagedRuntimeService(LaunchEntry entry, HostSecurityContext securityContext, RuntimeServiceEngine engine) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    void start() {
        startedAt = Instant.now();
        engine.start();
    }

    boolean live() {
        return engine.live();
    }

    boolean ready() {
        return engine.ready();
    }

    RuntimeServiceSnapshot snapshot() {
        return new RuntimeServiceSnapshot(
                entry.role().id(),
                entry.processFamily(),
                securityContext.identity().instanceId().value(),
                securityContext.identity().instanceKind(),
                securityContext.identity().principalId().value(),
                securityContext.credentialRef(),
                live(),
                ready(),
                engine.loopCount(),
                startedAt);
    }

    @Override
    public void close() {
        engine.close();
    }
}
