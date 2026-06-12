package sh.harold.fulcrum.runtime.threading;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RuntimeInbox {
    private final Queue<RuntimeIntent> queue;
    private final int maxDrainPerTick;
    private final Logger logger;

    public RuntimeInbox(int capacity, int maxDrainPerTick, Logger logger) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (maxDrainPerTick <= 0) {
            throw new IllegalArgumentException("maxDrainPerTick must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxDrainPerTick = maxDrainPerTick;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean enqueue(RuntimeIntent intent, PaperRuntime runtime) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(runtime, "runtime");

        if (!runtime.isActive(intent.epoch())) {
            logger.warning("Refused stale runtime intent '" + intent.operation() + "'");
            return false;
        }
        if (!queue.offer(intent)) {
            runtime.reportViolation("runtime inbox enqueue: " + intent.operation(),
                "runtime inbox has reached capacity " + queue.size());
            return false;
        }
        return true;
    }

    public int drain(PaperRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        runtime.requirePrimary("runtime inbox drain");

        int drained = 0;
        while (drained < maxDrainPerTick) {
            RuntimeIntent intent = queue.poll();
            if (intent == null) {
                break;
            }
            drained++;
            if (!runtime.isActive(intent.epoch())) {
                logger.fine("Discarded stale runtime intent '" + intent.operation() + "'");
                continue;
            }
            try {
                intent.run(runtime);
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Runtime intent failed: " + intent.operation(), exception);
            }
        }
        return drained;
    }

    public int size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }
}
