package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationHttpServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ControllerRuntimeServiceEngine implements RuntimeServiceEngine {
    private final List<ControllerWorkerBinding> workers;
    private final Duration idleDelay;
    private final Optional<RuntimeConnectionSettings.HostPort> authorityRegistrationBind;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong loopCount = new AtomicLong();
    private CapabilityBackendRegistrationHttpServer registrationServer;
    private Thread thread;

    ControllerRuntimeServiceEngine(List<ControllerWorkerBinding> workers, Duration idleDelay) {
        this(workers, idleDelay, Optional.empty());
    }

    ControllerRuntimeServiceEngine(
            List<ControllerWorkerBinding> workers,
            Duration idleDelay,
            Optional<RuntimeConnectionSettings.HostPort> authorityRegistrationBind) {
        this.workers = List.copyOf(Objects.requireNonNull(workers, "workers"));
        if (this.workers.isEmpty()) {
            throw new IllegalArgumentException("controller-service requires at least one controller worker");
        }
        this.idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isNegative() || idleDelay.isZero()) {
            throw new IllegalArgumentException("idleDelay must be positive");
        }
        this.authorityRegistrationBind = Objects.requireNonNull(
                authorityRegistrationBind,
                "authorityRegistrationBind");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("controller-service is already running");
        }
        try {
            authorityRegistrationBind.ifPresent(bind -> registrationServer =
                    CapabilityBackendRegistrationHttpServer.start(
                            new InetSocketAddress(bind.host(), bind.port()),
                            new CapabilityBackendRegistrationController()));
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
        thread = new Thread(this::runLoop, "fulcrum-controller-runtime");
        thread.setDaemon(false);
        thread.start();
    }

    @Override
    public boolean live() {
        Thread current = thread;
        return running.get() && current != null && current.isAlive();
    }

    @Override
    public boolean ready() {
        return ready.get();
    }

    @Override
    public long loopCount() {
        return loopCount.get();
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
        if (registrationServer != null) {
            registrationServer.close();
            registrationServer = null;
        }
    }

    private void runLoop() {
        try {
            ready.set(true);
            while (running.get()) {
                boolean handled = false;
                for (ControllerWorkerBinding worker : workers) {
                    if (!running.get()) {
                        break;
                    }
                    loopCount.incrementAndGet();
                    try {
                        handled = worker.handleNext().isPresent() || handled;
                    } catch (RuntimeException exception) {
                        if (!running.get() && Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        throw exception;
                    }
                }
                if (!handled) {
                    sleep(idleDelay);
                }
            }
        } finally {
            ready.set(false);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
