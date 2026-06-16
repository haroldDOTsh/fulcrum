package sh.harold.fulcrum.data.route;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
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
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RouteAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T14:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-06-16T14:00:30Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-route-controller");
    private static final RouteId ROUTE = new RouteId("route-hub-1");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final SessionId TARGET_SESSION = new SessionId("session-hub-1");
    private static final InstanceId TARGET_INSTANCE = new InstanceId("instance-paper-1");

    @Test
    void opensPendingRouteWithTransitionReceiptAndEmissions() {
        RouteAuthority authority = authority();

        AuthorityDecision<RouteState, RouteReceipt> decision = authority.handle(
                command("command-route-1", "idempotency-route-1", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-1"),
                RouteAuthority.emptyRecord(11));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(RouteReceiptStatus.ACCEPTED, decision.response().status());
        assertEquals(Optional.of(ROUTE), decision.response().routeId());
        assertEquals(Optional.of(SUBJECT), decision.response().subjectId());
        assertEquals(Optional.of(11L), decision.response().fencingEpoch());
        assertEquals(Optional.of(RouteLifecycleStatus.PENDING), decision.response().lifecycleStatus());
        assertTrue(decision.state().current().isPresent());
        RouteSnapshot snapshot = decision.state().current().orElseThrow();
        assertEquals(ROUTE, snapshot.routeId());
        assertEquals(SUBJECT, snapshot.subjectId());
        assertEquals(TARGET_SESSION, snapshot.targetSessionId());
        assertEquals(TARGET_INSTANCE, snapshot.targetInstanceId());
        assertEquals(RouteLifecycleStatus.PENDING, snapshot.status());
        assertEquals("trace-route", decision.traceEnvelope().traceId());
        assertEquals(
                java.util.List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                RouteAuthority.cacheKey(ROUTE),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateOpenReplaysStoredReceipt() {
        RouteAuthority authority = authority();
        AuthorityRecord<RouteState> initial = RouteAuthority.emptyRecord(11);
        AuthorityCommand<RouteCommand> command = command(
                "command-route-2",
                "idempotency-route-2",
                PRINCIPAL,
                PRINCIPAL,
                11,
                Optional.of(new Revision(0)),
                open(),
                "payload-2");

        AuthorityDecision<RouteState, RouteReceipt> accepted = authority.handle(command, initial);
        AuthorityDecision<RouteState, RouteReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(accepted.revision(), 11, accepted.state()));

        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void conflictingIdempotencyPayloadRejectsWithoutReplacingStoredReceipt() {
        RouteAuthority authority = authority();
        AuthorityCommand<RouteCommand> original = command(
                "command-route-3",
                "idempotency-route-3",
                PRINCIPAL,
                PRINCIPAL,
                11,
                Optional.of(new Revision(0)),
                open(),
                "payload-3");
        AuthorityDecision<RouteState, RouteReceipt> accepted = authority.handle(
                original,
                RouteAuthority.emptyRecord(11));

        AuthorityDecision<RouteState, RouteReceipt> conflict = authority.handle(
                command(
                        "command-route-4",
                        "idempotency-route-3",
                        PRINCIPAL,
                        PRINCIPAL,
                        11,
                        Optional.of(new Revision(1)),
                        new OpenRoute(
                                ROUTE,
                                SUBJECT,
                                new SessionId("session-hub-2"),
                                new InstanceId("instance-paper-2"),
                                NOW,
                                EXPIRES_AT),
                        "payload-4"),
                new AuthorityRecord<>(accepted.revision(), 11, accepted.state()));

        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(RouteReceiptStatus.REJECTED, conflict.response().status());

        AuthorityDecision<RouteState, RouteReceipt> replay = authority.handle(
                original,
                new AuthorityRecord<>(accepted.revision(), 11, accepted.state()));
        assertEquals(accepted.response(), replay.response());
    }

    @Test
    void staleFencingRevisionAndPrincipalMismatchesRejectBeforeRouteMutation() {
        RouteAuthority authority = authority();

        assertRejected(
                authority.handle(
                        command("command-route-5", "idempotency-route-5", PRINCIPAL, PRINCIPAL, 10, Optional.of(new Revision(0)), open(), "payload-5"),
                        RouteAuthority.emptyRecord(11)),
                AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertRejected(
                authority.handle(
                        command("command-route-6", "idempotency-route-6", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(7)), open(), "payload-6"),
                        RouteAuthority.emptyRecord(11)),
                AuthorityRejectionReason.REVISION_MISMATCH);
        assertRejected(
                authority.handle(
                        command("command-route-7", "idempotency-route-7", PRINCIPAL, new PrincipalId("principal-other-route"), 11, Optional.of(new Revision(0)), open(), "payload-7"),
                        RouteAuthority.emptyRecord(11)),
                AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void acknowledgementAndTimeoutClosePendingRoutes() {
        RouteAuthority acknowledgeAuthority = authority();
        AuthorityDecision<RouteState, RouteReceipt> opened = acknowledgeAuthority.handle(
                command("command-route-9", "idempotency-route-9", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-9"),
                RouteAuthority.emptyRecord(11));

        Instant acknowledgedAt = Instant.parse("2026-06-16T14:00:10Z");
        AuthorityDecision<RouteState, RouteReceipt> acknowledged = acknowledgeAuthority.handle(
                command(
                        "command-route-10",
                        "idempotency-route-10",
                        PRINCIPAL,
                        PRINCIPAL,
                        11,
                        Optional.of(new Revision(1)),
                        new AcknowledgeRoute(ROUTE, SUBJECT, TARGET_SESSION, TARGET_INSTANCE, acknowledgedAt),
                        "payload-10"),
                new AuthorityRecord<>(opened.revision(), 11, opened.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, acknowledged.status());
        assertEquals(RouteLifecycleStatus.ACKNOWLEDGED, acknowledged.state().current().orElseThrow().status());
        assertEquals(Optional.of(acknowledgedAt), acknowledged.state().current().orElseThrow().completedAt());
        assertEquals(Optional.of(RouteLifecycleStatus.ACKNOWLEDGED), acknowledged.response().lifecycleStatus());
        assertTrue(acknowledged.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=ACKNOWLEDGED"));

        RouteAuthority timeoutAuthority = authority();
        AuthorityDecision<RouteState, RouteReceipt> pending = timeoutAuthority.handle(
                command("command-route-11", "idempotency-route-11", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-11"),
                RouteAuthority.emptyRecord(11));
        Instant timedOutAt = Instant.parse("2026-06-16T14:00:35Z");
        AuthorityDecision<RouteState, RouteReceipt> timedOut = timeoutAuthority.handle(
                command(
                        "command-route-12",
                        "idempotency-route-12",
                        PRINCIPAL,
                        PRINCIPAL,
                        11,
                        Optional.of(new Revision(1)),
                        new TimeoutRoute(ROUTE, timedOutAt),
                        "payload-12",
                        timedOutAt),
                new AuthorityRecord<>(pending.revision(), 11, pending.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, timedOut.status());
        assertEquals(RouteLifecycleStatus.TIMED_OUT, timedOut.state().current().orElseThrow().status());
        assertEquals(Optional.of(timedOutAt), timedOut.state().current().orElseThrow().completedAt());
        assertEquals(Optional.of(RouteLifecycleStatus.TIMED_OUT), timedOut.response().lifecycleStatus());
        assertTrue(timedOut.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=TIMED_OUT"));
    }

    @Test
    void staleFencingEpochRejectsPausedOwnerBeforeRouteClosure() {
        RouteAuthority authority = authority();
        AuthorityDecision<RouteState, RouteReceipt> opened = authority.handle(
                command("command-route-13", "idempotency-route-13", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-13"),
                RouteAuthority.emptyRecord(11));

        AuthorityDecision<RouteState, RouteReceipt> staleOwner = authority.handle(
                command(
                        "command-route-14",
                        "idempotency-route-14",
                        PRINCIPAL,
                        PRINCIPAL,
                        11,
                        Optional.of(new Revision(1)),
                        new AcknowledgeRoute(
                                ROUTE,
                                SUBJECT,
                                TARGET_SESSION,
                                TARGET_INSTANCE,
                                Instant.parse("2026-06-16T14:00:10Z")),
                        "payload-14"),
                new AuthorityRecord<>(opened.revision(), 12, opened.state()));

        assertRejected(staleOwner, AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertEquals(RouteLifecycleStatus.PENDING, staleOwner.state().current().orElseThrow().status());
    }

    @Test
    void closedOrExpiredRouteCannotBeAcknowledgedWithNewIdempotencyKey() {
        RouteAuthority authority = authority();
        AuthorityDecision<RouteState, RouteReceipt> opened = authority.handle(
                command("command-route-15", "idempotency-route-15", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-15"),
                RouteAuthority.emptyRecord(11));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-route-16",
                                "idempotency-route-16",
                                PRINCIPAL,
                                PRINCIPAL,
                                11,
                                Optional.of(new Revision(1)),
                                new AcknowledgeRoute(
                                        ROUTE,
                                        SUBJECT,
                                        TARGET_SESSION,
                                        TARGET_INSTANCE,
                                        Instant.parse("2026-06-16T14:00:40Z")),
                                "payload-16",
                                Instant.parse("2026-06-16T14:00:40Z")),
                        new AuthorityRecord<>(opened.revision(), 11, opened.state())));

        RouteAuthority timeoutAuthority = authority();
        AuthorityDecision<RouteState, RouteReceipt> pending = timeoutAuthority.handle(
                command("command-route-17", "idempotency-route-17", PRINCIPAL, PRINCIPAL, 11, Optional.of(new Revision(0)), open(), "payload-17"),
                RouteAuthority.emptyRecord(11));
        AuthorityDecision<RouteState, RouteReceipt> timedOut = timeoutAuthority.handle(
                command(
                        "command-route-18",
                        "idempotency-route-18",
                        PRINCIPAL,
                        PRINCIPAL,
                        11,
                        Optional.of(new Revision(1)),
                        new TimeoutRoute(ROUTE, Instant.parse("2026-06-16T14:00:35Z")),
                        "payload-18",
                        Instant.parse("2026-06-16T14:00:35Z")),
                new AuthorityRecord<>(pending.revision(), 11, pending.state()));

        assertThrows(
                IllegalStateException.class,
                () -> timeoutAuthority.handle(
                        command(
                                "command-route-19",
                                "idempotency-route-19",
                                PRINCIPAL,
                                PRINCIPAL,
                                11,
                                Optional.of(new Revision(2)),
                                new AcknowledgeRoute(
                                        ROUTE,
                                        SUBJECT,
                                        TARGET_SESSION,
                                        TARGET_INSTANCE,
                                        Instant.parse("2026-06-16T14:00:36Z")),
                                "payload-19",
                                Instant.parse("2026-06-16T14:00:36Z")),
                        new AuthorityRecord<>(timedOut.revision(), 11, timedOut.state())));
    }

    @Test
    void openRoutePayloadContainsRoutingFieldsOnly() {
        assertEquals(
                java.util.Set.of("routeId", "subjectId", "targetSessionId", "targetInstanceId", "requestedAt", "expiresAt"),
                java.util.Arrays.stream(OpenRoute.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aggregateMustBeKeyedByRoute() {
        RouteAuthority authority = authority();

        assertThrows(
                IllegalArgumentException.class,
                () -> authority.handle(
                        command(
                                "command-route-8",
                                "idempotency-route-8",
                                PRINCIPAL,
                                PRINCIPAL,
                                11,
                                Optional.of(new Revision(0)),
                                open(),
                                "payload-8",
                                new AggregateId("subject:" + SUBJECT.value())),
                        RouteAuthority.emptyRecord(11)));
    }

    private static RouteAuthority authority() {
        return new RouteAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static OpenRoute open() {
        return new OpenRoute(
                ROUTE,
                SUBJECT,
                TARGET_SESSION,
                TARGET_INSTANCE,
                NOW,
                EXPIRES_AT);
    }

    private static AuthorityCommand<RouteCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            RouteCommand payload,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payload,
                payloadFingerprint,
                RouteAuthority.aggregateId(payload.routeId()),
                NOW);
    }

    private static AuthorityCommand<RouteCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            RouteCommand payload,
            String payloadFingerprint,
            Instant receivedAt) {
        return command(
                commandId,
                idempotencyKey,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payload,
                payloadFingerprint,
                RouteAuthority.aggregateId(payload.routeId()),
                receivedAt);
    }

    private static AuthorityCommand<RouteCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            RouteCommand payload,
            String payloadFingerprint,
            AggregateId aggregateId) {
        return command(
                commandId,
                idempotencyKey,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payload,
                payloadFingerprint,
                aggregateId,
                NOW);
    }

    private static AuthorityCommand<RouteCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            RouteCommand payload,
            String payloadFingerprint,
            AggregateId aggregateId,
            Instant receivedAt) {
        CommandEnvelope<RouteCommand> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                new ContractName("route"),
                new CommandName(commandName(payload)),
                new TraceEnvelope(
                        "trace-route",
                        "span-route",
                        Optional.empty(),
                        NOW,
                        "route-authority-test",
                        new InstanceId("instance-route-authority-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                receivedAt);
    }

    private static String commandName(RouteCommand payload) {
        if (payload instanceof OpenRoute) {
            return "open-route";
        }
        if (payload instanceof AcknowledgeRoute) {
            return "acknowledge-route";
        }
        return "timeout-route";
    }

    private static void assertRejected(
            AuthorityDecision<RouteState, RouteReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(RouteReceiptStatus.REJECTED, decision.response().status());
    }
}
