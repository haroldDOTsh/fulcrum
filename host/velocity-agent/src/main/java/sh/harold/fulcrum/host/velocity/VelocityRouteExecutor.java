package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public final class VelocityRouteExecutor {
    private final VelocityBackendRegistry backendRegistry;
    private final Clock clock;

    public VelocityRouteExecutor(
            HostSecurityContext securityContext,
            String proxyRouteCommandTopic,
            VelocityBackendRegistry backendRegistry,
            Clock clock) {
        Objects.requireNonNull(securityContext, "securityContext");
        proxyRouteCommandTopic = requireNonBlank(proxyRouteCommandTopic, "proxyRouteCommandTopic");
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
        return backendRegistry.transfer(command.subjectId(), backendName)
                .thenApply(transferred -> transferred
                        ? Optional.of(new VelocityRouteTransfer(
                                command.routeId(),
                                command.subjectId(),
                                command.targetSessionId(),
                                command.targetInstanceId(),
                                clock.instant()))
                        : Optional.empty());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
