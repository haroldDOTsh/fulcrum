package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.RouteContracts;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.util.Objects;
import java.util.Optional;

public final class VelocityRouteCommandFactory {
    private final HostSecurityContext securityContext;

    public VelocityRouteCommandFactory(HostSecurityContext securityContext) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        if (!HostInstanceKinds.VELOCITY.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("Velocity route commands require a Velocity Instance identity");
        }
    }

    public CommandEnvelope<RouteCommand> acknowledgeRoute(
            CommandId commandId,
            IdempotencyKey idempotencyKey,
            TraceEnvelope traceEnvelope,
            VelocityRouteTransfer routeTransfer) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        Objects.requireNonNull(routeTransfer, "routeTransfer");
        requireRouteCommandGrant();

        AcknowledgeRoute payload = new AcknowledgeRoute(
                routeTransfer.routeId(),
                routeTransfer.subjectId(),
                routeTransfer.targetSessionId(),
                routeTransfer.targetInstanceId(),
                routeTransfer.acknowledgedAt());
        return new CommandEnvelope<>(
                commandId,
                idempotencyKey,
                securityContext.identity().principalId(),
                RouteContracts.aggregateId(routeTransfer.routeId()),
                RouteContracts.CONTRACT_NAME,
                RouteContracts.commandName(payload),
                traceEnvelope,
                Optional.empty(),
                payload);
    }

    private void requireRouteCommandGrant() {
        if (!securityContext.credentialScope().permits(
                HostResourceFamily.TOPIC,
                HostAccessMode.PRODUCE,
                RouteContracts.COMMAND_TOPIC)) {
            throw new SecurityException("Velocity Instance is not allowed to produce route commands");
        }
    }
}
