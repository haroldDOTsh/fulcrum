package sh.harold.fulcrum.registry.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.maintenance.MaintenanceContext;
import sh.harold.fulcrum.api.maintenance.MaintenanceScope;
import sh.harold.fulcrum.api.maintenance.MaintenanceStatus;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.network.MaintenanceToggleMessage;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates registry-driven maintenance toggles across Redis and the message bus.
 */
public final class MaintenanceCoordinator {
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    public static final UUID CONSOLE_ACTOR = new UUID(0L, 1L);

    private static final String KEY_PREFIX = "maintenance:scopes:";

    private final RedisManager redisManager;
    private final MessageBus messageBus;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final Lock lock = new ReentrantLock();
    private final Map<MaintenanceScope, MaintenanceContext> contexts = new EnumMap<>(MaintenanceScope.class);
    private final Map<UUID, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();

    public MaintenanceCoordinator(RedisManager redisManager,
                                  MessageBus messageBus,
                                  ScheduledExecutorService scheduler,
                                  Logger logger) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void initialize() {
        lock.lock();
        try {
            for (MaintenanceScope scope : MaintenanceScope.values()) {
                reloadScope(scope);
            }
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        expiryTasks.values().forEach(task -> {
            if (task != null) {
                task.cancel(false);
            }
        });
        expiryTasks.clear();
    }

    public CompletionStage<MaintenanceSnapshot> updateScope(MaintenanceScope scope,
                                                            MaintenanceStatus desiredStatus,
                                                            Optional<UUID> requestedContext,
                                                            Optional<Instant> expiresAt,
                                                            UUID actor) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(desiredStatus, "desiredStatus");
        Objects.requireNonNull(actor, "actor");

        lock.lock();
        try {
            MaintenanceContext current = contexts.get(scope);
            Instant now = Instant.now();

            if (desiredStatus == MaintenanceStatus.ON) {
                MaintenanceContext updated = upsertContext(scope, current, requestedContext, expiresAt, actor, now);
                persist(scope, updated);
                scheduleExpiry(updated);
                publishUpdate(updated, MaintenanceStatus.ON, actor, updated.updatedAt(), updated.expiresAt());
                logState(scope, updated, "ENABLED");
            } else {
                if (current == null) {
                    logger.info("Maintenance scope {} already inactive", scope.name().toLowerCase(Locale.ROOT));
                    return CompletableFuture.completedFuture(snapshot());
                }
                if (requestedContext.isPresent() && !requestedContext.get().equals(current.id())) {
                    throw new IllegalArgumentException("Scope " + scope.key()
                            + " is managed by context " + current.shortId()
                            + "; supply --id " + current.id() + " to mutate it");
                }
                removeScope(scope);
                publishUpdate(current, MaintenanceStatus.OFF, actor, now, current.expiresAt());
                logState(scope, current, "DISABLED");
            }

            return CompletableFuture.completedFuture(snapshot());
        } finally {
            lock.unlock();
        }
    }

    public MaintenanceSnapshot snapshot() {
        lock.lock();
        try {
            return new MaintenanceSnapshot(Map.copyOf(contexts));
        } finally {
            lock.unlock();
        }
    }

    private MaintenanceContext upsertContext(MaintenanceScope scope,
                                             MaintenanceContext current,
                                             Optional<UUID> requestedContext,
                                             Optional<Instant> expiresAt,
                                             UUID actor,
                                             Instant updatedAt) {
        if (current != null && requestedContext.isEmpty()) {
            throw new IllegalArgumentException("Scope " + scope.key()
                    + " already has an active context (" + current.shortId()
                    + "). Use --id " + current.id() + " to mutate it or disable first.");
        }

        if (current == null && requestedContext.isPresent()) {
            logger.info("Creating maintenance context {} for scope {}", requestedContext.get(), scope.key());
        }

        if (current != null && requestedContext.isPresent() && !current.id().equals(requestedContext.get())) {
            throw new IllegalArgumentException("Scope " + scope.key()
                    + " is managed by context " + current.shortId()
                    + "; supply --id " + current.id() + " to mutate it");
        }

        UUID id = requestedContext.orElseGet(UUID::randomUUID);
        Instant expiry = expiresAt.orElse(null);
        MaintenanceContext context = new MaintenanceContext(
                id,
                scope,
                MaintenanceStatus.ON,
                updatedAt,
                actor,
                expiry
        );
        contexts.put(scope, context);
        return context;
    }

    private void reloadScope(MaintenanceScope scope) {
        try {
            String json = redisManager.sync().get(scopeKey(scope));
            if (json == null || json.isBlank()) {
                contexts.remove(scope);
                return;
            }

            MaintenanceContext context = mapper.readValue(json, MaintenanceContext.class);
            if (!context.isActive()) {
                contexts.remove(scope);
                redisManager.sync().del(scopeKey(scope));
                return;
            }
            if (context.expiresAt() != null && context.expiresAt().isBefore(Instant.now())) {
                contexts.remove(scope);
                redisManager.sync().del(scopeKey(scope));
                publishUpdate(context, MaintenanceStatus.OFF, SYSTEM_ACTOR, Instant.now(), context.expiresAt());
                return;
            }
            contexts.put(scope, context);
            scheduleExpiry(context);
            logger.info("Recovered maintenance context {} for scope {}", context.shortId(), scope.key());
        } catch (Exception ex) {
            logger.warn("Failed to reload maintenance scope {}", scope, ex);
        }
    }

    private void persist(MaintenanceScope scope, MaintenanceContext context) {
        try {
            redisManager.sync().set(scopeKey(scope), mapper.writeValueAsString(context));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist maintenance state for " + scope.key(), ex);
        }
    }

    private void removeScope(MaintenanceScope scope) {
        MaintenanceContext removed = contexts.remove(scope);
        if (removed != null) {
            cancelExpiry(removed.id());
        }
        redisManager.sync().del(scopeKey(scope));
    }

    private void scheduleExpiry(MaintenanceContext context) {
        cancelExpiry(context.id());
        Instant expiresAt = context.expiresAt();
        if (expiresAt == null) {
            return;
        }

        long delayMillis = Duration.between(Instant.now(), expiresAt).toMillis();
        if (delayMillis <= 0) {
            scheduler.execute(() -> expireContext(context.scope(), context.id()));
            return;
        }

        ScheduledFuture<?> task = scheduler.schedule(
                () -> expireContext(context.scope(), context.id()),
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        expiryTasks.put(context.id(), task);
    }

    private void expireContext(MaintenanceScope scope, UUID contextId) {
        lock.lock();
        try {
            MaintenanceContext current = contexts.get(scope);
            if (current == null || !current.id().equals(contextId)) {
                return;
            }
            removeScope(scope);
            Instant now = Instant.now();
            publishUpdate(current, MaintenanceStatus.OFF, SYSTEM_ACTOR, now, current.expiresAt());
            logState(scope, current, "AUTO-DISABLED");
        } finally {
            lock.unlock();
        }
    }

    private void cancelExpiry(UUID contextId) {
        ScheduledFuture<?> task = expiryTasks.remove(contextId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void publishUpdate(MaintenanceContext context,
                               MaintenanceStatus status,
                               UUID actor,
                               Instant updatedAt,
                               Instant expiresAt) {
        try {
            MaintenanceToggleMessage message = new MaintenanceToggleMessage(
                    context.id(),
                    context.scope(),
                    status,
                    updatedAt,
                    expiresAt,
                    actor
            );
            messageBus.broadcast(ChannelConstants.REGISTRY_MAINTENANCE_UPDATE, message);
        } catch (Exception ex) {
            logger.warn("Failed to publish maintenance update for {}", context.scope().key(), ex);
        }
    }

    private void logState(MaintenanceScope scope, MaintenanceContext context, String action) {
        logger.info("[{}] maintenance context {} scope={} expires={}",
                action,
                context.shortId(),
                scope.key(),
                context.expiresAt() != null ? context.expiresAt() : "N/A");
    }

    private String scopeKey(MaintenanceScope scope) {
        return KEY_PREFIX + scope.key();
    }
}
