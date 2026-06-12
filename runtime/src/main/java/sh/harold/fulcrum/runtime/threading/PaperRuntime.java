package sh.harold.fulcrum.runtime.threading;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public interface PaperRuntime extends AutoCloseable {
    JavaPlugin plugin();

    RuntimeEpoch epoch();

    ThreadPolicy policy();

    RuntimeInbox inbox();

    Executor asyncExecutor();

    boolean isPrimaryThread();

    boolean isActive(RuntimeEpoch epoch);

    void requirePrimary(String operation);

    void reportViolation(String operation, String detail);

    CompletableFuture<Void> runSync(String operation, Runnable task);

    <T> CompletableFuture<T> callSync(String operation, Supplier<T> task);

    CompletableFuture<Void> runAsync(String operation, Runnable task);

    <T> CompletableFuture<T> supplyAsync(String operation, Supplier<T> task);

    boolean enqueue(RuntimeIntent intent);

    @Override
    void close();
}
