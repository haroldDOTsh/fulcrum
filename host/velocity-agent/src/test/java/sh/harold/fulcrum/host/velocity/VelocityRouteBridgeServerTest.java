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
import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityRouteBridgeServerTest {
    private static final String PROXY_ROUTE_TOPIC = "host.velocity.routes";
    private static final Instant NOW = Instant.parse("2026-06-17T22:00:00Z");
    private static final RouteId ROUTE_ID = new RouteId("route-velocity-bridge-1");
    private static final SessionId SESSION_ID = new SessionId("session-lobby-shared");
    private static final InstanceId TARGET_INSTANCE = new InstanceId("instance-paper-target-1");
    private static final SubjectId SUBJECT_ID = new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @Test
    void localBridgeExecutesRouteTransferAndReturnsAcknowledgementPayload() {
        RecordingGateway gateway = new RecordingGateway(true);
        try (VelocityRouteBridgeServer server = new VelocityRouteBridgeServer(
                URI.create("http://127.0.0.1:0/routes"),
                executor(gateway))) {
            server.start();
            VelocityRouteBridgeClient client = new VelocityRouteBridgeClient(server.uri());

            Optional<VelocityRouteTransfer> transfer = client.execute(request());

            assertTrue(transfer.isPresent());
            assertEquals(ROUTE_ID, transfer.orElseThrow().routeId());
            assertEquals(SUBJECT_ID, transfer.orElseThrow().subjectId());
            assertEquals(SESSION_ID, transfer.orElseThrow().targetSessionId());
            assertEquals(TARGET_INSTANCE, transfer.orElseThrow().targetInstanceId());
            assertEquals(NOW, transfer.orElseThrow().acknowledgedAt());
            assertEquals(List.of(new RegisteredBackend(
                    "fulcrum-instance-paper-target-1",
                    request().endpoint().socketAddress())), gateway.registrations);
            assertEquals(List.of(new TransferRequest(
                    SUBJECT_ID,
                    "fulcrum-instance-paper-target-1")), gateway.transfers);
        }
    }

    @Test
    void localBridgeReturnsEmptyWhenVelocityTransferDoesNotComplete() {
        try (VelocityRouteBridgeServer server = new VelocityRouteBridgeServer(
                URI.create("http://127.0.0.1:0/routes"),
                executor(new RecordingGateway(false)))) {
            server.start();
            VelocityRouteBridgeClient client = new VelocityRouteBridgeClient(server.uri());

            assertTrue(client.execute(request()).isEmpty());
        }
    }

    @Test
    void bridgeCodecRoundTripsRequestAndTransferResponse() {
        VelocityRouteBridgeRequest request = request();
        VelocityRouteBridgeRequest decodedRequest = VelocityRouteBridgeCodec.decodeRequest(
                VelocityRouteBridgeCodec.encodeRequest(request));

        assertEquals(request, decodedRequest);

        VelocityRouteTransfer transfer = new VelocityRouteTransfer(
                ROUTE_ID,
                SUBJECT_ID,
                SESSION_ID,
                TARGET_INSTANCE,
                NOW);

        assertEquals(Optional.of(transfer), VelocityRouteBridgeCodec.decodeResponse(
                VelocityRouteBridgeCodec.encodeResponse(Optional.of(transfer))));
        assertTrue(VelocityRouteBridgeCodec.decodeResponse(
                VelocityRouteBridgeCodec.encodeResponse(Optional.empty())).isEmpty());
    }

    private static VelocityRouteBridgeRequest request() {
        return new VelocityRouteBridgeRequest(
                VelocityProxyRouteCommand.parse("proxy.route"
                        + "|routeAttemptId=route-attempt-bridge-1"
                        + "|routeId=" + ROUTE_ID.value()
                        + "|subjectId=" + SUBJECT_ID.value()
                        + "|sessionId=" + SESSION_ID.value()
                        + "|targetInstanceId=" + TARGET_INSTANCE.value()
                        + "|traceId=trace-velocity-bridge"),
                new VelocityBackendEndpoint(TARGET_INSTANCE, "10.96.10.25", 25565));
    }

    private static VelocityRouteExecutor executor(RecordingGateway gateway) {
        return new VelocityRouteExecutor(
                securityContext(),
                PROXY_ROUTE_TOPIC,
                new VelocityBackendRegistry(gateway),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-velocity-1"),
                        HostInstanceKinds.VELOCITY,
                        new PoolId("pool-velocity"),
                        new MachineRef("machine-velocity-1"),
                        new PrincipalId("principal-velocity-1")),
                "service-account:velocity-agent",
                HostCredentialScope.of(new HostResourceGrant(
                        HostResourceFamily.TOPIC,
                        HostAccessMode.CONSUME,
                        PROXY_ROUTE_TOPIC)));
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
