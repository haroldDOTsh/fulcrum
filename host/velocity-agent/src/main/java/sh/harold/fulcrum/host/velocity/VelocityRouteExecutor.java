package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class VelocityRouteExecutor {
    private static final Duration PENDING_INITIAL_ROUTE_GRACE = Duration.ofSeconds(1);

    private final VelocityBackendRegistry backendRegistry;
    private final Clock clock;
    private final VelocityInitialRouteCoordinator initialRouteCoordinator;
    private final Duration pendingInitialRouteGrace;

    public VelocityRouteExecutor(
            HostSecurityContext securityContext,
            String proxyRouteCommandTopic,
            VelocityBackendRegistry backendRegistry,
            Clock clock) {
        this(securityContext, proxyRouteCommandTopic, backendRegistry, clock, null);
    }

    VelocityRouteExecutor(
            HostSecurityContext securityContext,
            String proxyRouteCommandTopic,
            VelocityBackendRegistry backendRegistry,
            Clock clock,
            VelocityInitialRouteCoordinator initialRouteCoordinator) {
        this(securityContext, proxyRouteCommandTopic, backendRegistry, clock, initialRouteCoordinator,
                PENDING_INITIAL_ROUTE_GRACE);
    }

    VelocityRouteExecutor(
            HostSecurityContext securityContext,
            String proxyRouteCommandTopic,
            VelocityBackendRegistry backendRegistry,
            Clock clock,
            VelocityInitialRouteCoordinator initialRouteCoordinator,
            Duration pendingInitialRouteGrace) {
        Objects.requireNonNull(securityContext, "securityContext");
        proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
        pendingInitialRouteGrace = Objects.requireNonNull(pendingInitialRouteGrace, "pendingInitialRouteGrace");
        if (pendingInitialRouteGrace.isNegative() || pendingInitialRouteGrace.isZero()) {
            throw new IllegalArgumentException("pendingInitialRouteGrace must be positive");
        }
        if (!HostInstanceKinds.VELOCITY.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("Velocity route execution requires a Velocity Instance identity");
        }
        if (!securityContext.credentialScope().permits(
                HostResourceFamily.TOPIC,
                HostAccessMode.CONSUME,
                proxyRouteCommandTopic)) {
            throw new SecurityException("Velocity Instance is not allowed to consume proxy route commands");
        }
        this.backendRegistry = Objects.requireNonNull(backendRegistry, "backendRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.initialRouteCoordinator = initialRouteCoordinator;
        this.pendingInitialRouteGrace = pendingInitialRouteGrace;
    }

    public CompletionStage<Optional<VelocityRouteTransfer>> execute(
            VelocityProxyRouteCommand command,
            VelocityBackendEndpoint endpoint) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!command.targetInstanceId().equals(endpoint.instanceId())) {
            throw new IllegalArgumentException("route target Instance must match backend endpoint Instance");
        }
        String backendName = backendRegistry.ensureRegistered(endpoint);
        if (initialRouteCoordinator != null) {
            VelocityInitialRouteOffer initialRoute = initialRouteCoordinator.offerWithContext(
                    command.subjectId(),
                    backendName);
            if (initialRoute.belongsToInitialLogin()) {
                return initialRoute.accepted().thenApply(selected -> selected
                        ? Optional.of(transfer(command))
                        : Optional.empty());
            }
            CompletionStage<Boolean> directTransfer = backendRegistry.transfer(command.subjectId(), backendName);
            return firstSuccessfulTransfer(command, boundedInitialRoute(initialRoute.accepted()), directTransfer);
        }
        return backendRegistry.transfer(command.subjectId(), backendName)
                .thenApply(transferred -> transferred
                        ? Optional.of(transfer(command))
                        : Optional.empty());
    }

    private CompletionStage<Boolean> boundedInitialRoute(CompletionStage<Boolean> accepted) {
        CompletableFuture<Boolean> source = accepted.toCompletableFuture();
        CompletableFuture<Boolean> bounded = new CompletableFuture<>();
        source.whenComplete((selected, failure) -> {
            if (failure != null) {
                bounded.completeExceptionally(failure);
            } else {
                bounded.complete(Boolean.TRUE.equals(selected));
            }
        });
        CompletableFuture.delayedExecutor(pendingInitialRouteGrace.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (bounded.complete(false)) {
                        source.complete(false);
                    }
                });
        return bounded;
    }

    private CompletionStage<Optional<VelocityRouteTransfer>> firstSuccessfulTransfer(
            VelocityProxyRouteCommand command,
            CompletionStage<Boolean> initialRoute,
            CompletionStage<Boolean> directTransfer) {
        CompletableFuture<Optional<VelocityRouteTransfer>> result = new CompletableFuture<>();
        CompletableFuture<Boolean> initial = initialRoute.toCompletableFuture();
        CompletableFuture<Boolean> direct = directTransfer.toCompletableFuture();
        initial.whenComplete((selected, failure) -> completeRoute(command, result, selected, failure, direct));
        direct.whenComplete((transferred, failure) -> completeRoute(command, result, transferred, failure, initial));
        return result;
    }

    private void completeRoute(
            VelocityProxyRouteCommand command,
            CompletableFuture<Optional<VelocityRouteTransfer>> result,
            Boolean succeeded,
            Throwable failure,
            CompletableFuture<Boolean> other) {
        if (result.isDone()) {
            return;
        }
        if (failure != null) {
            result.completeExceptionally(failure);
            return;
        }
        if (Boolean.TRUE.equals(succeeded)) {
            result.complete(Optional.of(transfer(command)));
            return;
        }
        if (other.isDone() && !other.isCompletedExceptionally() && !other.isCancelled()) {
            result.complete(Optional.empty());
        }
    }

    private VelocityRouteTransfer transfer(VelocityProxyRouteCommand command) {
        return new VelocityRouteTransfer(
                command.routeId(),
                command.subjectId(),
                command.targetSessionId(),
                command.targetInstanceId(),
                clock.instant());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
