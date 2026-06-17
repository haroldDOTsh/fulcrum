package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityRouteExecutorTest {
    private static final String PROXY_ROUTE_TOPIC = "host.velocity.routes";
    private static final Instant NOW = Instant.parse("2026-06-17T21:00:00Z");
    private static final RouteId ROUTE_ID = new RouteId("route-velocity-transfer-1");
    private static final SessionId SESSION_ID = new SessionId("session-lobby-shared");
    private static final InstanceId TARGET_INSTANCE = new InstanceId("instance-paper-target-1");
    private static final SubjectId SUBJECT_ID = new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @Test
    void parsesRouteControllerProxyCommandWireValue() {
        VelocityProxyRouteCommand command = VelocityProxyRouteCommand.parse(proxyCommandWireValue());

        assertEquals("route-attempt-1", command.routeAttemptId());
        assertEquals(ROUTE_ID, command.routeId());
        assertEquals(SUBJECT_ID, command.subjectId());
        assertEquals(SESSION_ID, command.targetSessionId());
        assertEquals(TARGET_INSTANCE, command.targetInstanceId());
        assertEquals("trace-velocity-transfer", command.traceId());
    }

    @Test
    void registersBackendAndReturnsAcknowledgementTransferAfterSuccessfulVelocityTransfer() {
        RecordingGateway gateway = new RecordingGateway(true);
        VelocityRouteExecutor executor = executor(gateway);
        VelocityBackendEndpoint endpoint = new VelocityBackendEndpoint(TARGET_INSTANCE, "10.96.10.25", 25565);

        Optional<VelocityRouteTransfer> transfer = executor.execute(
                VelocityProxyRouteCommand.parse(proxyCommandWireValue()),
                endpoint).toCompletableFuture().join();

        assertTrue(transfer.isPresent());
        assertEquals(ROUTE_ID, transfer.orElseThrow().routeId());
        assertEquals(SUBJECT_ID, transfer.orElseThrow().subjectId());
        assertEquals(SESSION_ID, transfer.orElseThrow().targetSessionId());
        assertEquals(TARGET_INSTANCE, transfer.orElseThrow().targetInstanceId());
        assertEquals(NOW, transfer.orElseThrow().acknowledgedAt());
        assertEquals(List.of(new RegisteredBackend("fulcrum-instance-paper-target-1", endpoint.socketAddress())), gateway.registrations);
        assertEquals(List.of(new TransferRequest(SUBJECT_ID, "fulcrum-instance-paper-target-1")), gateway.transfers);
    }

    @Test
    void failedVelocityTransferReturnsNoAcknowledgementPayload() {
        RecordingGateway gateway = new RecordingGateway(false);
        VelocityRouteExecutor executor = executor(gateway);

        Optional<VelocityRouteTransfer> transfer = executor.execute(
                VelocityProxyRouteCommand.parse(proxyCommandWireValue()),
                new VelocityBackendEndpoint(TARGET_INSTANCE, "10.96.10.25", 25565)).toCompletableFuture().join();

        assertTrue(transfer.isEmpty());
    }

    @Test
    void routeTargetMustMatchBackendEndpointInstance() {
        VelocityRouteExecutor executor = executor(new RecordingGateway(true));

        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        VelocityProxyRouteCommand.parse(proxyCommandWireValue()),
                        new VelocityBackendEndpoint(new InstanceId("instance-other-paper"), "10.96.10.25", 25565)));
    }

    @Test
    void routeExecutionRequiresVelocityIdentityAndProxyCommandConsumeGrant() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VelocityRouteExecutor(
                        securityContext(HostInstanceKinds.PAPER, HostCredentialScope.of(proxyConsumeGrant())),
                        PROXY_ROUTE_TOPIC,
                        new VelocityBackendRegistry(new RecordingGateway(true)),
                        clock()));
        assertThrows(
                SecurityException.class,
                () -> new VelocityRouteExecutor(
                        securityContext(HostInstanceKinds.VELOCITY, HostCredentialScope.of()),
                        PROXY_ROUTE_TOPIC,
                        new VelocityBackendRegistry(new RecordingGateway(true)),
                        clock()));
    }

    private static VelocityRouteExecutor executor(RecordingGateway gateway) {
        return new VelocityRouteExecutor(
                securityContext(HostInstanceKinds.VELOCITY, HostCredentialScope.of(proxyConsumeGrant())),
                PROXY_ROUTE_TOPIC,
                new VelocityBackendRegistry(gateway),
                clock());
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static HostSecurityContext securityContext(String instanceKind, HostCredentialScope scope) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity-1"),
                        instanceKind,
                        new PoolId("pool-velocity"),
                        new MachineRef("machine-velocity-1"),
                        new PrincipalId("principal-velocity-1")),
                "service-account:velocity-agent",
                scope);
    }

    private static HostResourceGrant proxyConsumeGrant() {
        return new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, PROXY_ROUTE_TOPIC);
    }

    private static String proxyCommandWireValue() {
        return "proxy.route"
                + "|routeAttemptId=route-attempt-1"
                + "|routeId=" + ROUTE_ID.value()
                + "|subjectId=" + SUBJECT_ID.value()
                + "|sessionId=" + SESSION_ID.value()
                + "|targetInstanceId=" + TARGET_INSTANCE.value()
                + "|traceId=trace-velocity-transfer";
    }

    private record RegisteredBackend(String backendName, InetSocketAddress address) {
    }

    private record TransferRequest(SubjectId subjectId, String backendName) {
    }

    private static final class RecordingGateway implements VelocityProxyGateway {
        private final boolean transferResult;
        private final List<RegisteredBackend> registrations = new ArrayList<>();
        private final List<TransferRequest> transfers = new ArrayList<>();

        private RecordingGateway(boolean transferResult) {
            this.transferResult = transferResult;
        }

        @Override
        public void registerBackend(String backendName, InetSocketAddress address) {
            registrations.add(new RegisteredBackend(backendName, address));
        }

        @Override
        public CompletionStage<Boolean> transfer(SubjectId subjectId, String backendName) {
            transfers.add(new TransferRequest(subjectId, backendName));
            return CompletableFuture.completedFuture(transferResult);
        }
    }
}
