package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.RouteContracts;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VelocityRouteCommandFactoryTest {
    private static final Instant NOW = Instant.parse("2026-06-16T19:00:00Z");
    private static final PrincipalId VELOCITY_PRINCIPAL = new PrincipalId("principal-velocity-1");
    private static final RouteId ROUTE_ID = new RouteId("route-velocity-1");
    private static final SubjectId SUBJECT_ID = new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final SessionId TARGET_SESSION = new SessionId("session-target-1");
    private static final InstanceId TARGET_INSTANCE = new InstanceId("instance-paper-target-1");

    @Test
    void acknowledgementCommandUsesVelocityIdentityAndRouteContract() {
        VelocityRouteCommandFactory factory = new VelocityRouteCommandFactory(securityContext(HostInstanceKinds.VELOCITY, routeGrant()));

        CommandEnvelope<RouteCommand> envelope = factory.acknowledgeRoute(
                new CommandId("command-route-ack-1"),
                new IdempotencyKey("idempotency-route-ack-1"),
                traceEnvelope(),
                transfer());

        assertEquals(VELOCITY_PRINCIPAL, envelope.principalId());
        assertEquals(RouteContracts.aggregateId(ROUTE_ID), envelope.aggregateId());
        assertEquals(RouteContracts.CONTRACT_NAME, envelope.contractName());
        assertEquals("acknowledge-route", envelope.commandName().value());
        AcknowledgeRoute payload = assertInstanceOf(AcknowledgeRoute.class, envelope.payload());
        assertEquals(ROUTE_ID, payload.routeId());
        assertEquals(SUBJECT_ID, payload.subjectId());
        assertEquals(TARGET_SESSION, payload.targetSessionId());
        assertEquals(TARGET_INSTANCE, payload.targetInstanceId());
        assertEquals(NOW, payload.acknowledgedAt());
    }

    @Test
    void acknowledgementRequiresRouteCommandProduceGrant() {
        VelocityRouteCommandFactory factory = new VelocityRouteCommandFactory(
                securityContext(HostInstanceKinds.VELOCITY, HostCredentialScope.of()));

        assertThrows(
                SecurityException.class,
                () -> factory.acknowledgeRoute(
                        new CommandId("command-route-ack-2"),
                        new IdempotencyKey("idempotency-route-ack-2"),
                        traceEnvelope(),
                        transfer()));
    }

    @Test
    void routeCommandFactoryRejectsNonVelocityIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityRouteCommandFactory(securityContext(HostInstanceKinds.PAPER, routeGrant())));
    }

    private static VelocityRouteTransfer transfer() {
        return new VelocityRouteTransfer(
                ROUTE_ID,
                SUBJECT_ID,
                TARGET_SESSION,
                TARGET_INSTANCE,
                NOW);
    }

    private static HostSecurityContext securityContext(String instanceKind, HostCredentialScope scope) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity-1"),
                        instanceKind,
                        new PoolId("pool-velocity"),
                        new MachineRef("machine-proxy-1"),
                        VELOCITY_PRINCIPAL),
                "service-account:velocity-agent",
                scope);
    }

    private static HostCredentialScope routeGrant() {
        return HostCredentialScope.of(new HostResourceGrant(
                HostResourceFamily.TOPIC,
                HostAccessMode.PRODUCE,
                RouteContracts.COMMAND_TOPIC));
    }

    private static TraceEnvelope traceEnvelope() {
        return new TraceEnvelope(
                "trace-velocity-route",
                "span-velocity-route",
                Optional.empty(),
                NOW,
                "velocity-agent-test",
                new InstanceId("instance-velocity-agent-test"));
    }
}
