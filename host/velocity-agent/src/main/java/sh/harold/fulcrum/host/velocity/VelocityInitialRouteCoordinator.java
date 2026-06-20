package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class VelocityInitialRouteCoordinator implements AutoCloseable {
    private final Object lock = new Object();
    private final Duration timeout;
    private final Function<SubjectId, Optional<String>> usernameLookup;
    private final ScheduledExecutorService scheduler;
    private final Map<SubjectId, VelocityInitialRouteSelection> pendingRoutes = new HashMap<>();
    private final Map<String, VelocityInitialRouteSelection> pendingRoutesByUsername = new HashMap<>();
    private final Map<SubjectId, CompletableFuture<Optional<VelocityInitialRouteSelection>>> waiters = new HashMap<>();
    private final Map<String, CompletableFuture<Optional<VelocityInitialRouteSelection>>> waitersByUsername = new HashMap<>();

    VelocityInitialRouteCoordinator(Duration timeout) {
        this(timeout, ignored -> Optional.empty());
    }

    VelocityInitialRouteCoordinator(Duration timeout, Function<SubjectId, Optional<String>> usernameLookup) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.usernameLookup = Objects.requireNonNull(usernameLookup, "usernameLookup");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fulcrum-velocity-initial-route-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletionStage<Boolean> offer(SubjectId subjectId, String backendName) {
        return offerWithContext(subjectId, backendName).accepted();
    }

    VelocityInitialRouteOffer offerWithContext(SubjectId subjectId, String backendName) {
        SubjectId checkedSubjectId = Objects.requireNonNull(subjectId, "subjectId");
        String checkedBackendName = requireNonBlank(backendName, "backendName");
        Optional<String> username = usernameLookup.apply(checkedSubjectId).map(VelocityInitialRouteCoordinator::usernameKey);
        VelocityInitialRouteSelection selection = new VelocityInitialRouteSelection(checkedSubjectId, checkedBackendName);
        CompletableFuture<Optional<VelocityInitialRouteSelection>> waiter;
        synchronized (lock) {
            VelocityInitialRouteSelection replaced = pendingRoutes.put(checkedSubjectId, selection);
            if (replaced != null) {
                replaced.acknowledge(false);
            }
            username.ifPresent(value -> {
                VelocityInitialRouteSelection replacedByUsername = pendingRoutesByUsername.put(value, selection);
                if (replacedByUsername != null && replacedByUsername != selection) {
                    replacedByUsername.acknowledge(false);
                }
            });
            waiter = waiters.remove(checkedSubjectId);
            if (waiter == null && username.isPresent()) {
                waiter = waitersByUsername.remove(username.orElseThrow());
            }
            if (waiter != null) {
                CompletableFuture<Optional<VelocityInitialRouteSelection>> matchedWaiter = waiter;
                waiters.values().removeIf(value -> value == matchedWaiter);
                waitersByUsername.values().removeIf(value -> value == matchedWaiter);
            }
        }
        if (waiter != null) {
            waiter.complete(Optional.of(selection));
        }
        scheduler.schedule(() -> expireRoute(checkedSubjectId, selection), timeout.toMillis(), TimeUnit.MILLISECONDS);
        selection.accepted().whenComplete((accepted, failure) -> removeRoute(checkedSubjectId, selection));
        return new VelocityInitialRouteOffer(selection.accepted(), waiter != null, username.isPresent());
    }

    CompletionStage<Optional<VelocityInitialRouteSelection>> await(SubjectId subjectId) {
        return await(subjectId, Optional.empty());
    }

    CompletionStage<Optional<VelocityInitialRouteSelection>> await(SubjectId subjectId, String username) {
        return await(subjectId, Optional.of(username));
    }

    private CompletionStage<Optional<VelocityInitialRouteSelection>> await(
            SubjectId subjectId,
            Optional<String> username) {
        SubjectId checkedSubjectId = Objects.requireNonNull(subjectId, "subjectId");
        Optional<String> checkedUsername = Objects.requireNonNull(username, "username")
                .map(VelocityInitialRouteCoordinator::usernameKey);
        CompletableFuture<Optional<VelocityInitialRouteSelection>> waiter = new CompletableFuture<>();
        VelocityInitialRouteSelection selection;
        synchronized (lock) {
            selection = pendingRoutes.get(checkedSubjectId);
            if (selection == null && checkedUsername.isPresent()) {
                selection = pendingRoutesByUsername.get(checkedUsername.orElseThrow());
            }
            if (selection == null) {
                CompletableFuture<Optional<VelocityInitialRouteSelection>> replaced =
                        waiters.put(checkedSubjectId, waiter);
                if (replaced != null) {
                    replaced.complete(Optional.empty());
                }
                checkedUsername.ifPresent(value -> {
                    CompletableFuture<Optional<VelocityInitialRouteSelection>> replacedByUsername =
                            waitersByUsername.put(value, waiter);
                    if (replacedByUsername != null && replacedByUsername != waiter) {
                        replacedByUsername.complete(Optional.empty());
                    }
                });
            }
        }
        if (selection != null) {
            return CompletableFuture.completedFuture(Optional.of(selection));
        }
        scheduler.schedule(() -> expireWaiter(checkedSubjectId, waiter), timeout.toMillis(), TimeUnit.MILLISECONDS);
        return waiter;
    }

    @Override
    public void close() {
        Map<SubjectId, VelocityInitialRouteSelection> routes;
        Map<SubjectId, CompletableFuture<Optional<VelocityInitialRouteSelection>>> waiting;
        synchronized (lock) {
            routes = Map.copyOf(pendingRoutes);
            waiting = Map.copyOf(waiters);
            pendingRoutes.clear();
            pendingRoutesByUsername.clear();
            waiters.clear();
            waitersByUsername.clear();
        }
        waiting.values().forEach(waiter -> waiter.complete(Optional.empty()));
        routes.values().forEach(route -> route.acknowledge(false));
        scheduler.shutdownNow();
    }

    private void expireRoute(SubjectId subjectId, VelocityInitialRouteSelection selection) {
        boolean removed = false;
        synchronized (lock) {
            if (pendingRoutes.get(subjectId) == selection) {
                pendingRoutes.remove(subjectId);
                removed = true;
            }
            pendingRoutesByUsername.values().removeIf(value -> value == selection);
        }
        if (removed) {
            selection.acknowledge(false);
        }
    }

    private void expireWaiter(
            SubjectId subjectId,
            CompletableFuture<Optional<VelocityInitialRouteSelection>> waiter) {
        boolean removed = false;
        synchronized (lock) {
            if (waiters.get(subjectId) == waiter) {
                waiters.remove(subjectId);
                removed = true;
            }
            if (waitersByUsername.values().removeIf(value -> value == waiter)) {
                removed = true;
            }
        }
        if (removed) {
            waiter.complete(Optional.empty());
        }
    }

    private void removeRoute(SubjectId subjectId, VelocityInitialRouteSelection selection) {
        synchronized (lock) {
            if (pendingRoutes.get(subjectId) == selection) {
                pendingRoutes.remove(subjectId);
            }
            pendingRoutesByUsername.values().removeIf(value -> value == selection);
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String usernameKey(String username) {
        return requireNonBlank(username, "username").toLowerCase(Locale.ROOT);
    }
}

final class VelocityInitialRouteSelection {
    private final SubjectId subjectId;
    private final String backendName;
    private final CompletableFuture<Boolean> accepted = new CompletableFuture<>();

    VelocityInitialRouteSelection(SubjectId subjectId, String backendName) {
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.backendName = Objects.requireNonNull(backendName, "backendName");
    }

    SubjectId subjectId() {
        return subjectId;
    }

    String backendName() {
        return backendName;
    }

    void acknowledge(boolean selected) {
        accepted.complete(selected);
    }

    CompletionStage<Boolean> accepted() {
        return accepted;
    }
}

record VelocityInitialRouteOffer(
        CompletionStage<Boolean> accepted,
        boolean matchedWaitingInitialServerEvent,
        boolean loginSubjectKnown) {

    boolean belongsToInitialLogin() {
        return matchedWaitingInitialServerEvent;
    }
}
