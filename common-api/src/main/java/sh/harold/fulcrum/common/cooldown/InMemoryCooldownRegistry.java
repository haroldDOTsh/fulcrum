package sh.harold.fulcrum.common.cooldown;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory implementation backed by concurrent maps and a background reaper.
 */
public final class InMemoryCooldownRegistry implements CooldownRegistry {

    private static final Duration REAPER_IDLE_POLL = Duration.ofMillis(5);
    private static final Logger LOGGER = Logger.getLogger(InMemoryCooldownRegistry.class.getName());

    private final Clock clock;
    private final ConcurrentMap<CooldownKey, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<CooldownKey, CooldownKey> parents = new ConcurrentHashMap<>();
    private final DelayQueue<Expiry> queue = new DelayQueue<>();
    private final AtomicLong stampGenerator = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Thread reaperThread;

    public InMemoryCooldownRegistry() {
        this(Clock.systemUTC());
    }

    public InMemoryCooldownRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reaperThread = buildReaperThread();
    }

    private Thread buildReaperThread() {
        Runnable task = this::runReaperLoop;
        try {
            return Thread.ofVirtual()
                    .name("cooldown-reaper-" + Integer.toHexString(System.identityHashCode(this)))
                    .start(task);
        } catch (UnsupportedOperationException ignored) {
            return Thread.ofPlatform()
                    .name("cooldown-reaper-" + Integer.toHexString(System.identityHashCode(this)))
                    .daemon(true)
                    .start(task);
        }
    }

    private void runReaperLoop() {
        while (running.get()) {
            if (paused.get()) {
                LockSupport.parkNanos(REAPER_IDLE_POLL.toNanos());
                continue;
            }
            try {
                Expiry expiry = queue.take();
                processExpiry(expiry);
            } catch (InterruptedException interrupted) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Throwable throwable) {
                LOGGER.log(Level.WARNING, "Cooldown reaper failure", throwable);
            }
        }
    }

    private void processExpiry(Expiry expiry) {
        CooldownKey canonical = canonical(expiry.key());
        store.computeIfPresent(canonical, (key, entry) -> {
            if (entry.stamp != expiry.stamp()) {
                return entry;
            }
            Instant now = clock.instant();
            if (entry.expiresAt.isAfter(now)) {
                return entry;
            }
            return null;
        });
    }

    @Override
    public CompletionStage<CooldownAcquisition> acquire(CooldownKey key, CooldownSpec spec) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(spec, "spec");

        CooldownKey canonical = canonical(key);
        CompletableFuture<CooldownAcquisition> future = new CompletableFuture<>();
        try {
            Instant now = clock.instant();
            store.compute(canonical, (ignored, entry) -> handleAcquire(canonical, spec, now, entry, future));
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private Entry handleAcquire(CooldownKey canonical,
                                CooldownSpec spec,
                                Instant now,
                                Entry entry,
                                CompletableFuture<CooldownAcquisition> future) {
        if (entry == null || entry.isExpired(now)) {
            Entry created = createEntry(canonical, now, spec.window());
            future.complete(new CooldownAcquisition.Accepted(new CooldownTicket(canonical, created.expiresAt)));
            return created;
        }
        if (spec.policy() == CooldownPolicy.EXTEND_ON_ACQUIRE) {
            Entry created = createEntry(canonical, now, spec.window());
            future.complete(new CooldownAcquisition.Accepted(new CooldownTicket(canonical, created.expiresAt)));
            return created;
        }
        Duration remaining = Duration.between(now, entry.expiresAt);
        if (remaining.isNegative()) {
            Entry created = createEntry(canonical, now, spec.window());
            future.complete(new CooldownAcquisition.Accepted(new CooldownTicket(canonical, created.expiresAt)));
            return created;
        }
        future.complete(new CooldownAcquisition.Rejected(remaining));
        return entry;
    }

    private Entry createEntry(CooldownKey key, Instant now, Duration window) {
        Instant expiresAt = now.plus(window);
        long stamp = stampGenerator.incrementAndGet();
        queue.offer(new Expiry(key, stamp, computeDeadlineNanos(window)));
        return new Entry(expiresAt, stamp);
    }

    private long computeDeadlineNanos(Duration window) {
        long nanos;
        try {
            nanos = window.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        if (nanos <= 0) {
            nanos = 1L;
        }
        long deadline = System.nanoTime() + nanos;
        if (deadline < 0) {
            return Long.MAX_VALUE;
        }
        return deadline;
    }

    @Override
    public Optional<Duration> remaining(CooldownKey key) {
        Objects.requireNonNull(key, "key");
        CooldownKey canonical = canonical(key);
        Entry entry = store.get(canonical);
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (entry.isExpired(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, entry.expiresAt));
    }

    @Override
    public void clear(CooldownKey key) {
        Objects.requireNonNull(key, "key");
        CooldownKey canonical = canonical(key);
        store.remove(canonical);
    }

    @Override
    public void link(CooldownKey primary, CooldownKey... aliases) {
        Objects.requireNonNull(primary, "primary");
        if (aliases == null || aliases.length == 0) {
            return;
        }
        CooldownKey primaryRoot = canonical(primary);
        for (CooldownKey alias : aliases) {
            Objects.requireNonNull(alias, "alias");
            CooldownKey aliasRoot = canonical(alias);
            if (primaryRoot.equals(aliasRoot)) {
                continue;
            }
            parents.put(aliasRoot, primaryRoot);
            migrateEntry(primaryRoot, aliasRoot);
        }
    }

    private void migrateEntry(CooldownKey targetRoot, CooldownKey sourceRoot) {
        Entry entry = store.remove(sourceRoot);
        if (entry == null) {
            return;
        }
        store.merge(targetRoot, entry, (existing, incoming) ->
                existing.expiresAt.isAfter(incoming.expiresAt) ? existing : incoming);
    }

    @Override
    public void pauseReaper() {
        paused.set(true);
        reaperThread.interrupt();
    }

    @Override
    public void resumeReaper() {
        paused.set(false);
        reaperThread.interrupt();
    }

    @Override
    public int drainOnce(int maxBatch) {
        if (maxBatch < 0) {
            throw new IllegalArgumentException("maxBatch must be >= 0");
        }
        if (maxBatch == 0) {
            return 0;
        }
        int removed = 0;
        Instant now = clock.instant();
        Iterator<Map.Entry<CooldownKey, Entry>> iterator = store.entrySet().iterator();
        while (iterator.hasNext() && removed < maxBatch) {
            Map.Entry<CooldownKey, Entry> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int trackedCount() {
        return store.size();
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            reaperThread.interrupt();
            try {
                reaperThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        store.clear();
        parents.clear();
        queue.clear();
    }

    private CooldownKey canonical(CooldownKey key) {
        parents.computeIfAbsent(key, entry -> key);
        CooldownKey current = key;
        CooldownKey parent = parents.get(current);
        while (parent != null && !parent.equals(current)) {
            current = parent;
            parent = parents.get(current);
        }
        CooldownKey root = current;
        current = key;
        while (!current.equals(root)) {
            CooldownKey next = parents.get(current);
            if (next == null) {
                break;
            }
            parents.put(current, root);
            current = next;
        }
        parents.put(root, root);
        return root;
    }

    private record Entry(Instant expiresAt, long stamp) {
        boolean isExpired(Instant now) {
            return expiresAt.isBefore(now);
        }
    }

    private record Expiry(CooldownKey key, long stamp, long deadlineNanos) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            long delay = deadlineNanos - System.nanoTime();
            return unit.convert(delay, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            long diff = deadlineNanos - ((Expiry) other).deadlineNanos;
            return Long.compare(diff, 0L);
        }
    }
}
