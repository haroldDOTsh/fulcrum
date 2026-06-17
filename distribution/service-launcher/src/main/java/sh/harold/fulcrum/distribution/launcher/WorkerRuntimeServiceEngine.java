package sh.harold.fulcrum.distribution.launcher;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class WorkerRuntimeServiceEngine implements RuntimeServiceEngine {
    private final List<WorkerJobBinding> workers;
    private final Duration idleDelay;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong loopCount = new AtomicLong();
    private Thread thread;

    WorkerRuntimeServiceEngine(List<WorkerJobBinding> workers, Duration idleDelay) {
        this.workers = List.copyOf(Objects.requireNonNull(workers, "workers"));
        if (this.workers.isEmpty()) {
            throw new IllegalArgumentException("worker-agent requires at least one worker binding");
        }
        this.idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isNegative() || idleDelay.isZero()) {
            throw new IllegalArgumentException("idleDelay must be positive");
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("worker-agent is already running");
        }
        thread = new Thread(this::runLoop, "fulcrum-worker-runtime");
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
    }

    private void runLoop() {
        try {
            ready.set(true);
            while (running.get()) {
                boolean handled = false;
                for (WorkerJobBinding worker : workers) {
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
