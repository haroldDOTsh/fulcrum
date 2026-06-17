package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.route.RouteAuthority;
import sh.harold.fulcrum.data.route.RouteLifecycleStatus;
import sh.harold.fulcrum.data.route.RouteReceipt;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RouteAuthorityWireCodecTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final RouteId ROUTE = new RouteId("route-codec-test");
    private static final SubjectId SUBJECT =
            new SubjectId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    private static final SessionId TARGET_SESSION = new SessionId("session-codec-test");
    private static final InstanceId TARGET_INSTANCE = new InstanceId("instance-paper-codec-test");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-route-runtime");

    @Test
    void commandPayloadRoundTripsThroughKafkaRecordWireFormat() {
        AuthorityCommand<RouteCommand> command = openCommand();

        AuthorityCommand<RouteCommand> decoded = RouteAuthorityWireCodec.decodeCommand(
                new ConsumerRecord<>(
                        "cmd.route",
                        0,
                        12L,
                        command.envelope().aggregateId().value(),
                        RouteAuthorityWireCodec.encodeCommand(command)));

        assertEquals(command.envelope().commandId(), decoded.envelope().commandId());
        assertEquals(command.envelope().idempotencyKey(), decoded.envelope().idempotencyKey());
        assertEquals(command.envelope().aggregateId(), decoded.envelope().aggregateId());
        assertEquals(command.authenticatedPrincipal(), decoded.authenticatedPrincipal());
        assertEquals(command.fencingEpoch(), decoded.fencingEpoch());
        assertEquals(command.expectedRevision(), decoded.expectedRevision());
        assertEquals(command.payloadFingerprint(), decoded.payloadFingerprint());
        assertEquals(command.receivedAt(), decoded.receivedAt());
        assertEquals(command.envelope().payload(), decoded.envelope().payload());
    }

    @Test
    void storedDecisionRoundTripsRouteStateAndReceipt() {
        var decision = new RouteAuthority(new InMemoryIdempotencyLedger<RouteState, RouteReceipt>())
                .handle(openCommand(), RouteAuthority.emptyRecord(7));
        StoredAuthorityDecision<RouteState, RouteReceipt> stored =
                new StoredAuthorityDecision<>("payload-route", decision);

        StoredAuthorityDecision<RouteState, RouteReceipt> decoded =
                RouteAuthorityWireCodec.decodeStoredDecision(RouteAuthorityWireCodec.encodeStoredDecision(stored));

        assertEquals("payload-route", decoded.payloadFingerprint());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decoded.decision().status());
        assertEquals(new Revision(1), decoded.decision().revision());
        assertEquals(ROUTE, decoded.decision().state().current().orElseThrow().routeId());
        assertEquals(RouteLifecycleStatus.PENDING, decoded.decision().state().current().orElseThrow().status());
        assertEquals(Optional.of(ROUTE), decoded.decision().response().routeId());
        assertEquals("trace-route-runtime", decoded.decision().traceEnvelope().traceId());
    }

    private static AuthorityCommand<RouteCommand> openCommand() {
        OpenRoute payload = new OpenRoute(
                ROUTE,
                SUBJECT,
                TARGET_SESSION,
                TARGET_INSTANCE,
                NOW,
                NOW.plusSeconds(30));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-open-route"),
                        new IdempotencyKey("idem-open-route"),
                        PRINCIPAL,
                        RouteAuthority.aggregateId(ROUTE),
                        new ContractName(RouteAuthorityWireCodec.CONTRACT),
                        new CommandName(RouteAuthorityWireCodec.OPEN_COMMAND),
                        new TraceEnvelope(
                                "trace-route-runtime",
                                "span-route-runtime",
                                Optional.empty(),
                                NOW,
                                "authority-service",
                                new InstanceId("instance-authority-service")),
                        Optional.of(NOW.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                7,
                Optional.of(new Revision(0)),
                "payload-open-route",
                NOW);
    }
}
