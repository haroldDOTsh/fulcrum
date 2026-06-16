package sh.harold.fulcrum.data.presence;

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
import sh.harold.fulcrum.api.kernel.PresenceId;
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

final class PresenceAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T13:30:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-06-16T13:31:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-velocity-edge");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final PresenceId PRESENCE = new PresenceId("presence-edge-1");
    private static final InstanceId OWNER_INSTANCE = new InstanceId("instance-velocity-1");
    private static final SessionId SESSION = new SessionId("session-lobby-1");
    private static final RouteId ROUTE = new RouteId("route-lobby-1");

    @Test
    void claimsLivePresenceWithHotReadEmissions() {
        PresenceAuthority authority = authority();

        AuthorityDecision<PresenceState, PresenceReceipt> decision = authority.handle(
                command("command-presence-1", "idempotency-presence-1", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-1"),
                PresenceAuthority.emptyRecord(9));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(PresenceReceiptStatus.ACCEPTED, decision.response().status());
        assertEquals(Optional.of(PRESENCE), decision.response().presenceId());
        assertEquals(Optional.of(SUBJECT), decision.response().subjectId());
        assertEquals(Optional.of(9L), decision.response().fencingEpoch());
        assertTrue(decision.state().current().isPresent());
        PresenceSnapshot snapshot = decision.state().current().orElseThrow();
        assertEquals(SUBJECT, snapshot.subjectId());
        assertEquals(OWNER_INSTANCE, snapshot.ownerInstanceId());
        assertEquals(Optional.of(SESSION), snapshot.sessionId());
        assertEquals(Optional.of(ROUTE), snapshot.routeId());
        assertEquals("trace-presence", decision.traceEnvelope().traceId());
        assertEquals(
                java.util.List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                PresenceAuthority.cacheKey(SUBJECT),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateClaimReplaysStoredReceipt() {
        PresenceAuthority authority = authority();
        AuthorityRecord<PresenceState> initial = PresenceAuthority.emptyRecord(9);
        AuthorityCommand<ClaimPresence> command = command(
                "command-presence-2",
                "idempotency-presence-2",
                PRINCIPAL,
                PRINCIPAL,
                9,
                Optional.of(new Revision(0)),
                payload(),
                "payload-2");

        AuthorityDecision<PresenceState, PresenceReceipt> accepted = authority.handle(command, initial);
        AuthorityDecision<PresenceState, PresenceReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(accepted.revision(), 9, accepted.state()));

        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void conflictingIdempotencyPayloadRejectsWithoutReplacingStoredReceipt() {
        PresenceAuthority authority = authority();
        AuthorityCommand<ClaimPresence> original = command(
                "command-presence-3",
                "idempotency-presence-3",
                PRINCIPAL,
                PRINCIPAL,
                9,
                Optional.of(new Revision(0)),
                payload(),
                "payload-3");
        AuthorityDecision<PresenceState, PresenceReceipt> accepted = authority.handle(
                original,
                PresenceAuthority.emptyRecord(9));

        AuthorityDecision<PresenceState, PresenceReceipt> conflict = authority.handle(
                command(
                        "command-presence-4",
                        "idempotency-presence-3",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(1)),
                        new ClaimPresence(
                                new PresenceId("presence-edge-2"),
                                SUBJECT,
                                OWNER_INSTANCE,
                                Optional.of(SESSION),
                                Optional.of(ROUTE),
                                NOW,
                                EXPIRES_AT),
                        "payload-4"),
                new AuthorityRecord<>(accepted.revision(), 9, accepted.state()));

        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(PresenceReceiptStatus.REJECTED, conflict.response().status());

        AuthorityDecision<PresenceState, PresenceReceipt> replay = authority.handle(
                original,
                new AuthorityRecord<>(accepted.revision(), 9, accepted.state()));
        assertEquals(accepted.response(), replay.response());
    }

    @Test
    void staleFencingRevisionAndPrincipalMismatchesRejectBeforePresenceMutation() {
        PresenceAuthority authority = authority();

        assertRejected(
                authority.handle(
                        command("command-presence-5", "idempotency-presence-5", PRINCIPAL, PRINCIPAL, 8, Optional.of(new Revision(0)), payload(), "payload-5"),
                        PresenceAuthority.emptyRecord(9)),
                AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertRejected(
                authority.handle(
                        command("command-presence-6", "idempotency-presence-6", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(7)), payload(), "payload-6"),
                        PresenceAuthority.emptyRecord(9)),
                AuthorityRejectionReason.REVISION_MISMATCH);
        assertRejected(
                authority.handle(
                        command("command-presence-7", "idempotency-presence-7", PRINCIPAL, new PrincipalId("principal-other-edge"), 9, Optional.of(new Revision(0)), payload(), "payload-7"),
                        PresenceAuthority.emptyRecord(9)),
                AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void claimPayloadContainsLivePresenceFieldsOnly() {
        assertEquals(
                java.util.Set.of("presenceId", "subjectId", "ownerInstanceId", "sessionId", "routeId", "observedAt", "expiresAt"),
                java.util.Arrays.stream(ClaimPresence.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aggregateMustBeKeyedBySubject() {
        PresenceAuthority authority = authority();

        assertThrows(
                IllegalArgumentException.class,
                () -> authority.handle(
                        command(
                                "command-presence-8",
                                "idempotency-presence-8",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(0)),
                                payload(),
                                "payload-8",
                                new AggregateId("presence:" + PRESENCE.value())),
                        PresenceAuthority.emptyRecord(9)));
    }

    private static PresenceAuthority authority() {
        return new PresenceAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static ClaimPresence payload() {
        return new ClaimPresence(
                PRESENCE,
                SUBJECT,
                OWNER_INSTANCE,
                Optional.of(SESSION),
                Optional.of(ROUTE),
                NOW,
                EXPIRES_AT);
    }

    private static AuthorityCommand<ClaimPresence> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            ClaimPresence payload,
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
                PresenceAuthority.aggregateId(payload.subjectId()));
    }

    private static AuthorityCommand<ClaimPresence> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            ClaimPresence payload,
            String payloadFingerprint,
            AggregateId aggregateId) {
        CommandEnvelope<ClaimPresence> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                new ContractName("presence"),
                new CommandName("claim-presence"),
                new TraceEnvelope(
                        "trace-presence",
                        "span-presence",
                        Optional.empty(),
                        NOW,
                        "presence-authority-test",
                        new InstanceId("instance-presence-authority-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(
            AuthorityDecision<PresenceState, PresenceReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(PresenceReceiptStatus.REJECTED, decision.response().status());
        assertFalse(decision.state().current().isPresent());
    }
}
