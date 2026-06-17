package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ManagedRuntimeService implements AutoCloseable {
    private final LaunchEntry entry;
    private final HostSecurityContext securityContext;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong loopCount = new AtomicLong();
    private Thread thread;
    private Instant startedAt;

    ManagedRuntimeService(LaunchEntry entry, HostSecurityContext securityContext) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(entry.role().id() + " is already running");
        }
        startedAt = Instant.now();
        thread = new Thread(this::runLoop, "fulcrum-" + entry.role().id());
        thread.setDaemon(false);
        thread.start();
    }

    boolean live() {
        Thread current = thread;
        return running.get() && current != null && current.isAlive();
    }

    boolean ready() {
        return ready.get();
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
                loopCount.get(),
                startedAt);
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ready.set(false);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        ready.set(true);
        while (running.get()) {
            loopCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running.set(false);
            }
        }
        ready.set(false);
    }
}
