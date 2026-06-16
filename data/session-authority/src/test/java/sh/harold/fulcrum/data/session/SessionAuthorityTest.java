package sh.harold.fulcrum.data.session;

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
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T15:00:00Z");
    private static final Instant LEASE_EXPIRES_AT = Instant.parse("2026-06-16T15:01:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-session-controller");
    private static final SessionId SESSION = new SessionId("session-match-1");
    private static final ExperienceId EXPERIENCE = new ExperienceId("experience-arena");
    private static final SlotId SLOT = new SlotId("slot-arena-1");
    private static final InstanceId OWNER_INSTANCE = new InstanceId("instance-paper-session-1");
    private static final SessionOwnerToken OWNER_TOKEN = new SessionOwnerToken("session-owner-token-1");
    private static final ResolvedManifestId RESOLVED_MANIFEST = new ResolvedManifestId("resolved-manifest-arena-1");

    @Test
    void opensPreparingSessionWithTransitionReceiptAndEmissions() {
        SessionAuthority authority = authority();

        AuthorityDecision<SessionState, SessionReceipt> decision = authority.handle(
                command("command-session-1", "idempotency-session-1", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-1"),
                SessionAuthority.emptyRecord(13));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(SessionReceiptStatus.ACCEPTED, decision.response().status());
        assertEquals(Optional.of(SESSION), decision.response().sessionId());
        assertEquals(Optional.of(13L), decision.response().fencingEpoch());
        assertEquals(Optional.of(1L), decision.response().ownerEpoch());
        assertEquals(Optional.of(SessionLifecycleStatus.PREPARING), decision.response().lifecycleStatus());
        SessionSnapshot snapshot = decision.state().current().orElseThrow();
        assertEquals(SESSION, snapshot.sessionId());
        assertEquals(EXPERIENCE, snapshot.experienceId());
        assertEquals(SLOT, snapshot.slotId());
        assertEquals(OWNER_INSTANCE, snapshot.ownerInstanceId());
        assertEquals(OWNER_TOKEN, snapshot.ownerToken());
        assertEquals(RESOLVED_MANIFEST, snapshot.resolvedManifestId());
        assertEquals(SessionLifecycleStatus.PREPARING, snapshot.status());
        assertEquals("trace-session", decision.traceEnvelope().traceId());
        assertEquals(
                java.util.List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                SessionAuthority.cacheKey(SESSION),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateOpenReplaysStoredReceipt() {
        SessionAuthority authority = authority();
        AuthorityRecord<SessionState> initial = SessionAuthority.emptyRecord(13);
        AuthorityCommand<SessionCommand> command = command(
                "command-session-2",
                "idempotency-session-2",
                PRINCIPAL,
                PRINCIPAL,
                13,
                Optional.of(new Revision(0)),
                open(),
                "payload-2");

        AuthorityDecision<SessionState, SessionReceipt> accepted = authority.handle(command, initial);
        AuthorityDecision<SessionState, SessionReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(accepted.revision(), 13, accepted.state()));

        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void conflictingIdempotencyPayloadRejectsWithoutReplacingStoredReceipt() {
        SessionAuthority authority = authority();
        AuthorityCommand<SessionCommand> original = command(
                "command-session-3",
                "idempotency-session-3",
                PRINCIPAL,
                PRINCIPAL,
                13,
                Optional.of(new Revision(0)),
                open(),
                "payload-3");
        AuthorityDecision<SessionState, SessionReceipt> accepted = authority.handle(
                original,
                SessionAuthority.emptyRecord(13));

        AuthorityDecision<SessionState, SessionReceipt> conflict = authority.handle(
                command(
                        "command-session-4",
                        "idempotency-session-3",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(1)),
                        new OpenSession(
                                SESSION,
                                new ExperienceId("experience-other"),
                                SLOT,
                                OWNER_INSTANCE,
                                OWNER_TOKEN,
                                RESOLVED_MANIFEST,
                                NOW,
                                LEASE_EXPIRES_AT),
                        "payload-4"),
                new AuthorityRecord<>(accepted.revision(), 13, accepted.state()));

        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(SessionReceiptStatus.REJECTED, conflict.response().status());

        AuthorityDecision<SessionState, SessionReceipt> replay = authority.handle(
                original,
                new AuthorityRecord<>(accepted.revision(), 13, accepted.state()));
        assertEquals(accepted.response(), replay.response());
    }

    @Test
    void staleFencingRevisionAndPrincipalMismatchesRejectBeforeSessionMutation() {
        SessionAuthority authority = authority();

        assertRejected(
                authority.handle(
                        command("command-session-5", "idempotency-session-5", PRINCIPAL, PRINCIPAL, 12, Optional.of(new Revision(0)), open(), "payload-5"),
                        SessionAuthority.emptyRecord(13)),
                AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertRejected(
                authority.handle(
                        command("command-session-6", "idempotency-session-6", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(7)), open(), "payload-6"),
                        SessionAuthority.emptyRecord(13)),
                AuthorityRejectionReason.REVISION_MISMATCH);
        assertRejected(
                authority.handle(
                        command("command-session-7", "idempotency-session-7", PRINCIPAL, new PrincipalId("principal-other-session"), 13, Optional.of(new Revision(0)), open(), "payload-7"),
                        SessionAuthority.emptyRecord(13)),
                AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void activationHeartbeatAndCloseAdvanceSessionLifecycle() {
        SessionAuthority authority = authority();
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(
                command("command-session-9", "idempotency-session-9", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-9"),
                SessionAuthority.emptyRecord(13));

        Instant activatedAt = Instant.parse("2026-06-16T15:00:10Z");
        Instant activeLease = Instant.parse("2026-06-16T15:02:00Z");
        AuthorityDecision<SessionState, SessionReceipt> activated = authority.handle(
                command(
                        "command-session-10",
                        "idempotency-session-10",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(1)),
                        new ActivateSession(SESSION, OWNER_TOKEN, 1, activatedAt, activeLease),
                        "payload-10"),
                new AuthorityRecord<>(opened.revision(), 13, opened.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, activated.status());
        assertEquals(SessionLifecycleStatus.ACTIVE, activated.state().current().orElseThrow().status());
        assertEquals(Optional.of(activatedAt), activated.state().current().orElseThrow().activatedAt());
        assertEquals(Optional.of(SessionLifecycleStatus.ACTIVE), activated.response().lifecycleStatus());
        assertTrue(activated.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=ACTIVATED"));

        Instant heartbeatAt = Instant.parse("2026-06-16T15:00:30Z");
        Instant heartbeatLease = Instant.parse("2026-06-16T15:03:00Z");
        AuthorityDecision<SessionState, SessionReceipt> heartbeat = authority.handle(
                command(
                        "command-session-11",
                        "idempotency-session-11",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(2)),
                        new HeartbeatSession(SESSION, OWNER_TOKEN, 1, heartbeatAt, heartbeatLease),
                        "payload-11",
                        heartbeatAt),
                new AuthorityRecord<>(activated.revision(), 13, activated.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, heartbeat.status());
        assertEquals(heartbeatLease, heartbeat.state().current().orElseThrow().leaseExpiresAt());
        assertEquals(SessionLifecycleStatus.ACTIVE, heartbeat.state().current().orElseThrow().status());
        assertTrue(heartbeat.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=HEARTBEAT"));

        Instant closedAt = Instant.parse("2026-06-16T15:00:40Z");
        AuthorityDecision<SessionState, SessionReceipt> closed = authority.handle(
                command(
                        "command-session-12",
                        "idempotency-session-12",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(3)),
                        new CloseSession(SESSION, OWNER_TOKEN, 1, closedAt, SessionCloseReason.COMPLETED),
                        "payload-12",
                        closedAt),
                new AuthorityRecord<>(heartbeat.revision(), 13, heartbeat.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, closed.status());
        assertEquals(SessionLifecycleStatus.ENDED, closed.state().current().orElseThrow().status());
        assertEquals(Optional.of(closedAt), closed.state().current().orElseThrow().closedAt());
        assertEquals(Optional.of(SessionCloseReason.COMPLETED), closed.state().current().orElseThrow().closeReason());
        assertEquals(Optional.of(SessionLifecycleStatus.ENDED), closed.response().lifecycleStatus());
    }

    @Test
    void staleOwnerTokenAndExpiredHeartbeatCannotMutateSession() {
        SessionAuthority authority = authority();
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(
                command("command-session-13", "idempotency-session-13", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-13"),
                SessionAuthority.emptyRecord(13));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-session-14",
                                "idempotency-session-14",
                                PRINCIPAL,
                                PRINCIPAL,
                                13,
                                Optional.of(new Revision(1)),
                                new HeartbeatSession(
                                        SESSION,
                                        new SessionOwnerToken("other-session-owner-token"),
                                        1,
                                        Instant.parse("2026-06-16T15:00:10Z"),
                                        Instant.parse("2026-06-16T15:02:00Z")),
                                "payload-14"),
                        new AuthorityRecord<>(opened.revision(), 13, opened.state())));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-session-15",
                                "idempotency-session-15",
                                PRINCIPAL,
                                PRINCIPAL,
                                13,
                                Optional.of(new Revision(1)),
                                new HeartbeatSession(
                                        SESSION,
                                        OWNER_TOKEN,
                                        1,
                                        Instant.parse("2026-06-16T15:01:10Z"),
                                        Instant.parse("2026-06-16T15:02:00Z")),
                                "payload-15",
                                Instant.parse("2026-06-16T15:01:10Z")),
                        new AuthorityRecord<>(opened.revision(), 13, opened.state())));
    }

    @Test
    void expiredLeaseCanBeFailedAndTerminalSessionCannotBeMutated() {
        SessionAuthority authority = authority();
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(
                command("command-session-16", "idempotency-session-16", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-16"),
                SessionAuthority.emptyRecord(13));

        Instant expiredAt = Instant.parse("2026-06-16T15:01:10Z");
        AuthorityDecision<SessionState, SessionReceipt> expired = authority.handle(
                command(
                        "command-session-17",
                        "idempotency-session-17",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(1)),
                        new ExpireSession(SESSION, expiredAt),
                        "payload-17",
                        expiredAt),
                new AuthorityRecord<>(opened.revision(), 13, opened.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, expired.status());
        assertEquals(SessionLifecycleStatus.FAILED, expired.state().current().orElseThrow().status());
        assertEquals(Optional.of(SessionCloseReason.LEASE_EXPIRED), expired.state().current().orElseThrow().closeReason());
        assertEquals(Optional.of(SessionLifecycleStatus.FAILED), expired.response().lifecycleStatus());
        assertTrue(expired.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=EXPIRED"));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-session-18",
                                "idempotency-session-18",
                                PRINCIPAL,
                                PRINCIPAL,
                                13,
                                Optional.of(new Revision(2)),
                                new CloseSession(SESSION, OWNER_TOKEN, 1, expiredAt.plusSeconds(1), SessionCloseReason.FAULTED),
                                "payload-18",
                                expiredAt.plusSeconds(1)),
                        new AuthorityRecord<>(expired.revision(), 13, expired.state())));
    }

    @Test
    void staleFencingEpochRejectsPausedOwnerBeforeSessionClosure() {
        SessionAuthority authority = authority();
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(
                command("command-session-19", "idempotency-session-19", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-19"),
                SessionAuthority.emptyRecord(13));

        AuthorityDecision<SessionState, SessionReceipt> staleOwner = authority.handle(
                command(
                        "command-session-20",
                        "idempotency-session-20",
                        PRINCIPAL,
                        PRINCIPAL,
                        13,
                        Optional.of(new Revision(1)),
                        new CloseSession(
                                SESSION,
                                OWNER_TOKEN,
                                1,
                                Instant.parse("2026-06-16T15:00:30Z"),
                                SessionCloseReason.RELEASED),
                        "payload-20"),
                new AuthorityRecord<>(opened.revision(), 14, opened.state()));

        assertRejected(staleOwner, AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertEquals(SessionLifecycleStatus.PREPARING, staleOwner.state().current().orElseThrow().status());
    }

    @Test
    void liveLeaseCannotBeExpiredBeforeDeadline() {
        SessionAuthority authority = authority();
        AuthorityDecision<SessionState, SessionReceipt> opened = authority.handle(
                command("command-session-21", "idempotency-session-21", PRINCIPAL, PRINCIPAL, 13, Optional.of(new Revision(0)), open(), "payload-21"),
                SessionAuthority.emptyRecord(13));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-session-22",
                                "idempotency-session-22",
                                PRINCIPAL,
                                PRINCIPAL,
                                13,
                                Optional.of(new Revision(1)),
                                new ExpireSession(SESSION, Instant.parse("2026-06-16T15:00:20Z")),
                                "payload-22",
                                Instant.parse("2026-06-16T15:00:20Z")),
                        new AuthorityRecord<>(opened.revision(), 13, opened.state())));
    }

    @Test
    void openSessionPayloadContainsPlatformLifecycleFieldsOnly() {
        assertEquals(
                java.util.Set.of("sessionId", "experienceId", "slotId", "ownerInstanceId", "ownerToken", "resolvedManifestId", "openedAt", "leaseExpiresAt"),
                java.util.Arrays.stream(OpenSession.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aggregateMustBeKeyedBySession() {
        SessionAuthority authority = authority();

        assertThrows(
                IllegalArgumentException.class,
                () -> authority.handle(
                        command(
                                "command-session-8",
                                "idempotency-session-8",
                                PRINCIPAL,
                                PRINCIPAL,
                                13,
                                Optional.of(new Revision(0)),
                                open(),
                                "payload-8",
                                new AggregateId("slot:" + SLOT.value())),
                        SessionAuthority.emptyRecord(13)));
    }

    private static SessionAuthority authority() {
        return new SessionAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static OpenSession open() {
        return new OpenSession(
                SESSION,
                EXPERIENCE,
                SLOT,
                OWNER_INSTANCE,
                OWNER_TOKEN,
                RESOLVED_MANIFEST,
                NOW,
                LEASE_EXPIRES_AT);
    }

    private static AuthorityCommand<SessionCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SessionCommand payload,
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
                SessionAuthority.aggregateId(payload.sessionId()),
                NOW);
    }

    private static AuthorityCommand<SessionCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SessionCommand payload,
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
                SessionAuthority.aggregateId(payload.sessionId()),
                receivedAt);
    }

    private static AuthorityCommand<SessionCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SessionCommand payload,
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

    private static AuthorityCommand<SessionCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SessionCommand payload,
            String payloadFingerprint,
            AggregateId aggregateId,
            Instant receivedAt) {
        CommandEnvelope<SessionCommand> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                new ContractName("session"),
                new CommandName(commandName(payload)),
                new TraceEnvelope(
                        "trace-session",
                        "span-session",
                        Optional.empty(),
                        NOW,
                        "session-authority-test",
                        new InstanceId("instance-session-authority-test")),
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

    private static String commandName(SessionCommand payload) {
        if (payload instanceof OpenSession) {
            return "open-session";
        }
        if (payload instanceof ActivateSession) {
            return "activate-session";
        }
        if (payload instanceof HeartbeatSession) {
            return "heartbeat-session";
        }
        if (payload instanceof CloseSession) {
            return "close-session";
        }
        return "expire-session";
    }

    private static void assertRejected(
            AuthorityDecision<SessionState, SessionReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(SessionReceiptStatus.REJECTED, decision.response().status());
    }
}
