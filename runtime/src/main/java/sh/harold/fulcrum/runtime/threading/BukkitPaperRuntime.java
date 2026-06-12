package sh.harold.fulcrum.runtime.threading;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BukkitPaperRuntime implements PaperRuntime {
    private static final int DEFAULT_INBOX_CAPACITY = 4096;
    private static final int DEFAULT_MAX_DRAIN_PER_TICK = 256;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ThreadPolicy policy;
    private final RuntimeEpoch epoch;
    private final RuntimeInbox inbox;
    private final ExecutorService asyncExecutor;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final BukkitTask drainTask;

    public BukkitPaperRuntime(JavaPlugin plugin, ThreadPolicy policy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.policy = Objects.requireNonNull(policy, "policy");
        this.epoch = RuntimeEpoch.create();
        this.inbox = new RuntimeInbox(DEFAULT_INBOX_CAPACITY, DEFAULT_MAX_DRAIN_PER_TICK, logger);
        this.asyncExecutor = Executors.newFixedThreadPool(defaultAsyncThreads(), new RuntimeThreadFactory(plugin.getName()));
        this.drainTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (active.get()) {
                inbox.drain(this);
            }
        }, 1L, 1L);
    }

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public RuntimeEpoch epoch() {
        return epoch;
    }

    @Override
    public ThreadPolicy policy() {
        return policy;
    }

    @Override
    public RuntimeInbox inbox() {
        return inbox;
    }

    @Override
    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isActive(RuntimeEpoch candidate) {
        return active.get() && epoch.matches(candidate);
    }

    @Override
    public void requirePrimary(String operation) {
        if (!isPrimaryThread()) {
            reportViolation(operation, "requires the Paper primary thread but ran on " + Thread.currentThread().getName());
        }
        if (!active.get()) {
            reportViolation(operation, "runtime is closed");
        }
    }

    @Override
    public void reportViolation(String operation, String detail) {
        String message = "Thread boundary violation during '" + operation + "': " + detail;
        if (policy == ThreadPolicy.FAIL_FAST) {
            throw new ThreadViolationException(message);
        }
        logger.warning(message);
        throw new ThreadViolationException(message);
    }

    @Override
    public CompletableFuture<Void> runSync(String operation, Runnable task) {
        Objects.requireNonNull(task, "task");
        return callSync(operation, () -> {
            task.run();
            return null;
        });
    }

    @Override
    public <T> CompletableFuture<T> callSync(String operation, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        RuntimeEpoch capturedEpoch = epoch;

        Runnable guarded = () -> {
            if (!isActive(capturedEpoch)) {
                future.completeExceptionally(new IllegalStateException("Runtime is no longer active for " + operation));
                return;
            }
            try {
                requirePrimary(operation);
                future.complete(task.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        };

        if (isPrimaryThread()) {
            guarded.run();
        } else if (!active.get() || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("Cannot schedule sync work after plugin disable: " + operation));
        } else {
            Bukkit.getScheduler().runTask(plugin, guarded);
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> runAsync(String operation, Runnable task) {
        Objects.requireNonNull(task, "task");
        return supplyAsync(operation, () -> {
            task.run();
            return null;
        });
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(String operation, Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        RuntimeEpoch capturedEpoch = epoch;

        if (!active.get()) {
            future.completeExceptionally(new IllegalStateException("Cannot schedule async work after runtime close: " + operation));
            return future;
        }

        asyncExecutor.execute(() -> {
            if (!isActive(capturedEpoch)) {
                future.completeExceptionally(new IllegalStateException("Runtime is no longer active for " + operation));
                return;
            }
            try {
                future.complete(task.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    @Override
    public boolean enqueue(RuntimeIntent intent) {
        return inbox.enqueue(intent, this);
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        try {
            drainTask.cancel();
        } catch (Exception exception) {
            logger.log(Level.FINE, "Failed to cancel runtime inbox drain task", exception);
        }
        inbox.clear();
        asyncExecutor.shutdownNow();
    }

    private static int defaultAsyncThreads() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static final class RuntimeThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();
        private final String pluginName;

        private RuntimeThreadFactory(String pluginName) {
            this.pluginName = pluginName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, pluginName + "-runtime-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
