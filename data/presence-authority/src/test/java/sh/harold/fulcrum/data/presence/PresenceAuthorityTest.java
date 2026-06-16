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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T13:30:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-06-16T13:31:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-velocity-edge");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final PresenceId PRESENCE = new PresenceId("presence-edge-1");
    private static final InstanceId OWNER_INSTANCE = new InstanceId("instance-velocity-1");
    private static final PresenceOwnerToken OWNER_TOKEN = new PresenceOwnerToken("owner-token-edge-1");
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
        assertEquals(Optional.of(1L), decision.response().ownerEpoch());
        assertEquals(Optional.of(PresenceLifecycleStatus.LIVE), decision.response().lifecycleStatus());
        assertTrue(decision.state().current().isPresent());
        PresenceSnapshot snapshot = decision.state().current().orElseThrow();
        assertEquals(SUBJECT, snapshot.subjectId());
        assertEquals(OWNER_INSTANCE, snapshot.ownerInstanceId());
        assertEquals(OWNER_TOKEN, snapshot.ownerToken());
        assertEquals(1, snapshot.ownerEpoch());
        assertEquals(PresenceLifecycleStatus.LIVE, snapshot.status());
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
        AuthorityCommand<PresenceCommand> command = command(
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
        AuthorityCommand<PresenceCommand> original = command(
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
                                new PresenceOwnerToken("owner-token-edge-2"),
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
    void heartbeatExtendsCurrentOwnerLeaseAndReleaseClosesPresence() {
        PresenceAuthority authority = authority();
        AuthorityDecision<PresenceState, PresenceReceipt> claimed = authority.handle(
                command("command-presence-9", "idempotency-presence-9", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-9"),
                PresenceAuthority.emptyRecord(9));

        Instant heartbeatAt = Instant.parse("2026-06-16T13:30:20Z");
        Instant heartbeatExpiresAt = Instant.parse("2026-06-16T13:32:00Z");
        AuthorityDecision<PresenceState, PresenceReceipt> heartbeat = authority.handle(
                command(
                        "command-presence-10",
                        "idempotency-presence-10",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(1)),
                        new HeartbeatPresence(SUBJECT, OWNER_TOKEN, 1, heartbeatAt, heartbeatExpiresAt),
                        "payload-10"),
                new AuthorityRecord<>(claimed.revision(), 9, claimed.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, heartbeat.status());
        PresenceSnapshot renewed = heartbeat.state().current().orElseThrow();
        assertEquals(heartbeatAt, renewed.observedAt());
        assertEquals(heartbeatExpiresAt, renewed.expiresAt());
        assertEquals(PresenceLifecycleStatus.LIVE, renewed.status());
        assertEquals(Optional.of(1L), heartbeat.response().ownerEpoch());
        assertEquals(Optional.of(PresenceLifecycleStatus.LIVE), heartbeat.response().lifecycleStatus());
        assertTrue(heartbeat.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=HEARTBEAT"));

        Instant releasedAt = Instant.parse("2026-06-16T13:30:30Z");
        AuthorityDecision<PresenceState, PresenceReceipt> release = authority.handle(
                command(
                        "command-presence-11",
                        "idempotency-presence-11",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(2)),
                        new ReleasePresence(SUBJECT, OWNER_TOKEN, 1, releasedAt, PresenceReleaseReason.DISCONNECTED),
                        "payload-11"),
                new AuthorityRecord<>(heartbeat.revision(), 9, heartbeat.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, release.status());
        PresenceSnapshot released = release.state().current().orElseThrow();
        assertEquals(PresenceLifecycleStatus.RELEASED, released.status());
        assertEquals(Optional.of(releasedAt), released.releasedAt());
        assertEquals(Optional.of(PresenceReleaseReason.DISCONNECTED), released.releaseReason());
        assertEquals(Optional.of(PresenceLifecycleStatus.RELEASED), release.response().lifecycleStatus());
        assertTrue(release.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=RELEASED"));
    }

    @Test
    void staleOwnerTokenAndExpiredHeartbeatCannotMutatePresence() {
        PresenceAuthority authority = authority();
        AuthorityDecision<PresenceState, PresenceReceipt> claimed = authority.handle(
                command("command-presence-12", "idempotency-presence-12", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-12"),
                PresenceAuthority.emptyRecord(9));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-presence-13",
                                "idempotency-presence-13",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(1)),
                                new HeartbeatPresence(
                                        SUBJECT,
                                        new PresenceOwnerToken("owner-token-other"),
                                        1,
                                        Instant.parse("2026-06-16T13:30:20Z"),
                                        Instant.parse("2026-06-16T13:32:00Z")),
                                "payload-13"),
                        new AuthorityRecord<>(claimed.revision(), 9, claimed.state())));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-presence-14",
                                "idempotency-presence-14",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(1)),
                                new HeartbeatPresence(
                                        SUBJECT,
                                        OWNER_TOKEN,
                                        1,
                                        Instant.parse("2026-06-16T13:31:20Z"),
                                        Instant.parse("2026-06-16T13:32:00Z")),
                                "payload-14",
                                Instant.parse("2026-06-16T13:31:20Z")),
                        new AuthorityRecord<>(claimed.revision(), 9, claimed.state())));
    }

    @Test
    void releasedPresenceCannotBeResurrectedByHeartbeatOrReleaseReplayWithNewIdempotencyKey() {
        PresenceAuthority authority = authority();
        AuthorityDecision<PresenceState, PresenceReceipt> claimed = authority.handle(
                command("command-presence-15", "idempotency-presence-15", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-15"),
                PresenceAuthority.emptyRecord(9));
        AuthorityDecision<PresenceState, PresenceReceipt> released = authority.handle(
                command(
                        "command-presence-16",
                        "idempotency-presence-16",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(1)),
                        new ReleasePresence(
                                SUBJECT,
                                OWNER_TOKEN,
                                1,
                                Instant.parse("2026-06-16T13:30:30Z"),
                                PresenceReleaseReason.DISCONNECTED),
                        "payload-16"),
                new AuthorityRecord<>(claimed.revision(), 9, claimed.state()));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-presence-17",
                                "idempotency-presence-17",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(2)),
                                new HeartbeatPresence(
                                        SUBJECT,
                                        OWNER_TOKEN,
                                        1,
                                        Instant.parse("2026-06-16T13:30:40Z"),
                                        Instant.parse("2026-06-16T13:32:00Z")),
                                "payload-17"),
                        new AuthorityRecord<>(released.revision(), 9, released.state())));
        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-presence-18",
                                "idempotency-presence-18",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(2)),
                                new ReleasePresence(
                                        SUBJECT,
                                        OWNER_TOKEN,
                                        1,
                                        Instant.parse("2026-06-16T13:30:45Z"),
                                        PresenceReleaseReason.DISCONNECTED),
                                "payload-18"),
                        new AuthorityRecord<>(released.revision(), 9, released.state())));
    }

    @Test
    void newClaimAfterExpiredLeaseAdvancesOwnerEpochAndFencesOldOwner() {
        PresenceAuthority authority = authority();
        AuthorityDecision<PresenceState, PresenceReceipt> claimed = authority.handle(
                command("command-presence-19", "idempotency-presence-19", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-19"),
                PresenceAuthority.emptyRecord(9));

        ClaimPresence replacementPayload = new ClaimPresence(
                new PresenceId("presence-edge-2"),
                SUBJECT,
                new InstanceId("instance-velocity-2"),
                new PresenceOwnerToken("owner-token-edge-2"),
                Optional.of(SESSION),
                Optional.of(ROUTE),
                Instant.parse("2026-06-16T13:31:10Z"),
                Instant.parse("2026-06-16T13:33:00Z"));
        AuthorityDecision<PresenceState, PresenceReceipt> replacement = authority.handle(
                command(
                        "command-presence-20",
                        "idempotency-presence-20",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(1)),
                        replacementPayload,
                        "payload-20",
                        Instant.parse("2026-06-16T13:31:10Z")),
                new AuthorityRecord<>(claimed.revision(), 9, claimed.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, replacement.status());
        assertEquals(Optional.of(2L), replacement.response().ownerEpoch());
        assertEquals(new PresenceOwnerToken("owner-token-edge-2"), replacement.state().current().orElseThrow().ownerToken());
        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-presence-21",
                                "idempotency-presence-21",
                                PRINCIPAL,
                                PRINCIPAL,
                                9,
                                Optional.of(new Revision(2)),
                                new HeartbeatPresence(
                                        SUBJECT,
                                        OWNER_TOKEN,
                                        1,
                                        Instant.parse("2026-06-16T13:31:20Z"),
                                        Instant.parse("2026-06-16T13:32:00Z")),
                                "payload-21",
                                Instant.parse("2026-06-16T13:31:20Z")),
                        new AuthorityRecord<>(replacement.revision(), 9, replacement.state())));
    }

    @Test
    void staleFencingEpochRejectsPausedOwnerBeforeLifecycleMutation() {
        PresenceAuthority authority = authority();
        AuthorityDecision<PresenceState, PresenceReceipt> claimed = authority.handle(
                command("command-presence-22", "idempotency-presence-22", PRINCIPAL, PRINCIPAL, 9, Optional.of(new Revision(0)), payload(), "payload-22"),
                PresenceAuthority.emptyRecord(9));

        AuthorityDecision<PresenceState, PresenceReceipt> staleOwner = authority.handle(
                command(
                        "command-presence-23",
                        "idempotency-presence-23",
                        PRINCIPAL,
                        PRINCIPAL,
                        9,
                        Optional.of(new Revision(1)),
                        new HeartbeatPresence(
                                SUBJECT,
                                OWNER_TOKEN,
                                1,
                                Instant.parse("2026-06-16T13:30:20Z"),
                                Instant.parse("2026-06-16T13:32:00Z")),
                        "payload-23"),
                new AuthorityRecord<>(claimed.revision(), 10, claimed.state()));

        assertRejected(staleOwner, AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertEquals(PresenceLifecycleStatus.LIVE, staleOwner.state().current().orElseThrow().status());
    }

    @Test
    void claimPayloadContainsLivePresenceFieldsOnly() {
        assertEquals(
                java.util.Set.of("presenceId", "subjectId", "ownerInstanceId", "ownerToken", "sessionId", "routeId", "observedAt", "expiresAt"),
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
                OWNER_TOKEN,
                Optional.of(SESSION),
                Optional.of(ROUTE),
                NOW,
                EXPIRES_AT);
    }

    private static AuthorityCommand<PresenceCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            PresenceCommand payload,
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
                PresenceAuthority.aggregateId(payload.subjectId()),
                NOW);
    }

    private static AuthorityCommand<PresenceCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            PresenceCommand payload,
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
                PresenceAuthority.aggregateId(payload.subjectId()),
                receivedAt);
    }

    private static AuthorityCommand<PresenceCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            PresenceCommand payload,
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

    private static AuthorityCommand<PresenceCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            PresenceCommand payload,
            String payloadFingerprint,
            AggregateId aggregateId,
            Instant receivedAt) {
        CommandEnvelope<PresenceCommand> envelope = new CommandEnvelope<>(
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
                receivedAt);
    }

    private static void assertRejected(
            AuthorityDecision<PresenceState, PresenceReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(PresenceReceiptStatus.REJECTED, decision.response().status());
    }
}
