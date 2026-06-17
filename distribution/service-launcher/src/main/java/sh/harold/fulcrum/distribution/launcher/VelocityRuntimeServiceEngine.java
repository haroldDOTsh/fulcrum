package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.velocity.VelocityLoginGateBridgeServer;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class VelocityRuntimeServiceEngine implements RuntimeServiceEngine {
    private final ExternalVelocityRouteWorker routeWorker;
    private final VelocityLoginGateBridgeServer loginGateBridgeServer;
    private final Duration idleDelay;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong loopCount = new AtomicLong();
    private Thread thread;

    VelocityRuntimeServiceEngine(
            ExternalVelocityRouteWorker routeWorker,
            VelocityLoginGateBridgeServer loginGateBridgeServer,
            Duration idleDelay) {
        this.routeWorker = Objects.requireNonNull(routeWorker, "routeWorker");
        this.loginGateBridgeServer = Objects.requireNonNull(loginGateBridgeServer, "loginGateBridgeServer");
        this.idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isNegative() || idleDelay.isZero()) {
            throw new IllegalArgumentException("idleDelay must be positive");
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("velocity-agent is already running");
        }
        try {
            loginGateBridgeServer.start();
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
        thread = new Thread(this::runLoop, "fulcrum-velocity-runtime");
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
        loginGateBridgeServer.close();
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
        try {
            ready.set(true);
            while (running.get()) {
                loopCount.incrementAndGet();
                Optional<VelocityRouteWorkerReceipt> receipt;
                try {
                    receipt = routeWorker.handleNext();
                } catch (RuntimeException exception) {
                    if (!running.get()) {
                        return;
                    }
                    throw exception;
                }
                if (receipt.isEmpty()) {
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
