package sh.harold.fulcrum.data.subject;

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

final class SubjectAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T16:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-identity-service");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final SubjectExternalIdentity EXTERNAL_IDENTITY =
            new SubjectExternalIdentity("minecraft:33333333-3333-3333-3333-333333333333");

    @Test
    void registersThinSubjectIdentityWithTransitionReceiptAndEmissions() {
        SubjectAuthority authority = authority();

        AuthorityDecision<SubjectState, SubjectReceipt> decision = authority.handle(
                command("command-subject-1", "idempotency-subject-1", PRINCIPAL, PRINCIPAL, 17, Optional.of(new Revision(0)), register(), "payload-1"),
                SubjectAuthority.emptyRecord(17));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(SubjectReceiptStatus.ACCEPTED, decision.response().status());
        assertEquals(Optional.of(SUBJECT), decision.response().subjectId());
        assertEquals(Optional.of(17L), decision.response().fencingEpoch());
        assertEquals(Optional.of(SubjectLifecycleStatus.ACTIVE), decision.response().lifecycleStatus());
        SubjectSnapshot snapshot = decision.state().current().orElseThrow();
        assertEquals(SUBJECT, snapshot.subjectId());
        assertEquals(SubjectIdentityProvider.MINECRAFT_ACCOUNT, snapshot.identityProvider());
        assertEquals(EXTERNAL_IDENTITY, snapshot.externalIdentity());
        assertEquals(PRINCIPAL, snapshot.registeredBy());
        assertEquals(SubjectLifecycleStatus.ACTIVE, snapshot.status());
        assertEquals("trace-subject", decision.traceEnvelope().traceId());
        assertEquals(
                java.util.List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                SubjectAuthority.cacheKey(SUBJECT),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateRegisterReplaysStoredReceipt() {
        SubjectAuthority authority = authority();
        AuthorityRecord<SubjectState> initial = SubjectAuthority.emptyRecord(17);
        AuthorityCommand<SubjectCommand> command = command(
                "command-subject-2",
                "idempotency-subject-2",
                PRINCIPAL,
                PRINCIPAL,
                17,
                Optional.of(new Revision(0)),
                register(),
                "payload-2");

        AuthorityDecision<SubjectState, SubjectReceipt> accepted = authority.handle(command, initial);
        AuthorityDecision<SubjectState, SubjectReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(accepted.revision(), 17, accepted.state()));

        assertEquals(accepted.response(), replay.response());
        assertTrue(replay.replayed());
    }

    @Test
    void conflictingIdempotencyPayloadRejectsWithoutReplacingStoredReceipt() {
        SubjectAuthority authority = authority();
        AuthorityCommand<SubjectCommand> original = command(
                "command-subject-3",
                "idempotency-subject-3",
                PRINCIPAL,
                PRINCIPAL,
                17,
                Optional.of(new Revision(0)),
                register(),
                "payload-3");
        AuthorityDecision<SubjectState, SubjectReceipt> accepted = authority.handle(
                original,
                SubjectAuthority.emptyRecord(17));

        AuthorityDecision<SubjectState, SubjectReceipt> conflict = authority.handle(
                command(
                        "command-subject-4",
                        "idempotency-subject-3",
                        PRINCIPAL,
                        PRINCIPAL,
                        17,
                        Optional.of(new Revision(1)),
                        new RegisterSubject(
                                SUBJECT,
                                SubjectIdentityProvider.PLATFORM_SERVICE,
                                new SubjectExternalIdentity("service:validator"),
                                NOW),
                        "payload-4"),
                new AuthorityRecord<>(accepted.revision(), 17, accepted.state()));

        assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(AuthorityRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(SubjectReceiptStatus.REJECTED, conflict.response().status());

        AuthorityDecision<SubjectState, SubjectReceipt> replay = authority.handle(
                original,
                new AuthorityRecord<>(accepted.revision(), 17, accepted.state()));
        assertEquals(accepted.response(), replay.response());
    }

    @Test
    void staleFencingRevisionAndPrincipalMismatchesRejectBeforeSubjectMutation() {
        SubjectAuthority authority = authority();

        assertRejected(
                authority.handle(
                        command("command-subject-5", "idempotency-subject-5", PRINCIPAL, PRINCIPAL, 16, Optional.of(new Revision(0)), register(), "payload-5"),
                        SubjectAuthority.emptyRecord(17)),
                AuthorityRejectionReason.STALE_FENCING_EPOCH);
        assertRejected(
                authority.handle(
                        command("command-subject-6", "idempotency-subject-6", PRINCIPAL, PRINCIPAL, 17, Optional.of(new Revision(7)), register(), "payload-6"),
                        SubjectAuthority.emptyRecord(17)),
                AuthorityRejectionReason.REVISION_MISMATCH);
        assertRejected(
                authority.handle(
                        command("command-subject-7", "idempotency-subject-7", PRINCIPAL, new PrincipalId("principal-other-identity"), 17, Optional.of(new Revision(0)), register(), "payload-7"),
                        SubjectAuthority.emptyRecord(17)),
                AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void retirementCreatesTombstoneWithAuthenticatedPrincipal() {
        SubjectAuthority authority = authority();
        AuthorityDecision<SubjectState, SubjectReceipt> registered = authority.handle(
                command("command-subject-9", "idempotency-subject-9", PRINCIPAL, PRINCIPAL, 17, Optional.of(new Revision(0)), register(), "payload-9"),
                SubjectAuthority.emptyRecord(17));

        PrincipalId retirementPrincipal = new PrincipalId("principal-identity-auditor");
        Instant retiredAt = Instant.parse("2026-06-16T16:05:00Z");
        AuthorityDecision<SubjectState, SubjectReceipt> retired = authority.handle(
                command(
                        "command-subject-10",
                        "idempotency-subject-10",
                        retirementPrincipal,
                        retirementPrincipal,
                        17,
                        Optional.of(new Revision(1)),
                        new RetireSubject(SUBJECT, retiredAt, SubjectRetireReason.ACCOUNT_CLOSED),
                        "payload-10",
                        retiredAt),
                new AuthorityRecord<>(registered.revision(), 17, registered.state()));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, retired.status());
        SubjectSnapshot snapshot = retired.state().current().orElseThrow();
        assertEquals(SubjectLifecycleStatus.RETIRED, snapshot.status());
        assertEquals(Optional.of(retirementPrincipal), snapshot.retiredBy());
        assertEquals(Optional.of(retiredAt), snapshot.retiredAt());
        assertEquals(Optional.of(SubjectRetireReason.ACCOUNT_CLOSED), snapshot.retireReason());
        assertEquals(Optional.of(SubjectLifecycleStatus.RETIRED), retired.response().lifecycleStatus());
        assertTrue(retired.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.EVENT)
                .findFirst()
                .orElseThrow()
                .payload()
                .contains("change=RETIRED"));
    }

    @Test
    void retiredSubjectCannotBeMutatedOrRegisteredAgainWithNewIdempotencyKey() {
        SubjectAuthority authority = authority();
        AuthorityDecision<SubjectState, SubjectReceipt> registered = authority.handle(
                command("command-subject-11", "idempotency-subject-11", PRINCIPAL, PRINCIPAL, 17, Optional.of(new Revision(0)), register(), "payload-11"),
                SubjectAuthority.emptyRecord(17));
        AuthorityDecision<SubjectState, SubjectReceipt> retired = authority.handle(
                command(
                        "command-subject-12",
                        "idempotency-subject-12",
                        PRINCIPAL,
                        PRINCIPAL,
                        17,
                        Optional.of(new Revision(1)),
                        new RetireSubject(SUBJECT, Instant.parse("2026-06-16T16:05:00Z"), SubjectRetireReason.ADMINISTRATIVE_RETIREMENT),
                        "payload-12"),
                new AuthorityRecord<>(registered.revision(), 17, registered.state()));

        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-subject-13",
                                "idempotency-subject-13",
                                PRINCIPAL,
                                PRINCIPAL,
                                17,
                                Optional.of(new Revision(2)),
                                new RetireSubject(SUBJECT, Instant.parse("2026-06-16T16:06:00Z"), SubjectRetireReason.DUPLICATE_IDENTITY),
                                "payload-13"),
                        new AuthorityRecord<>(retired.revision(), 17, retired.state())));
        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command(
                                "command-subject-14",
                                "idempotency-subject-14",
                                PRINCIPAL,
                                PRINCIPAL,
                                17,
                                Optional.of(new Revision(2)),
                                register(),
                                "payload-14"),
                        new AuthorityRecord<>(retired.revision(), 17, retired.state())));
    }

    @Test
    void registerPayloadContainsThinIdentityFieldsOnly() {
        assertEquals(
                java.util.Set.of("subjectId", "identityProvider", "externalIdentity", "registeredAt"),
                java.util.Arrays.stream(RegisterSubject.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aggregateMustBeKeyedBySubject() {
        SubjectAuthority authority = authority();

        assertThrows(
                IllegalArgumentException.class,
                () -> authority.handle(
                        command(
                                "command-subject-8",
                                "idempotency-subject-8",
                                PRINCIPAL,
                                PRINCIPAL,
                                17,
                                Optional.of(new Revision(0)),
                                register(),
                                "payload-8",
                                new AggregateId("identity:" + SUBJECT.value())),
                        SubjectAuthority.emptyRecord(17)));
    }

    private static SubjectAuthority authority() {
        return new SubjectAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static RegisterSubject register() {
        return new RegisterSubject(
                SUBJECT,
                SubjectIdentityProvider.MINECRAFT_ACCOUNT,
                EXTERNAL_IDENTITY,
                NOW);
    }

    private static AuthorityCommand<SubjectCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SubjectCommand payload,
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
                SubjectAuthority.aggregateId(payload.subjectId()),
                NOW);
    }

    private static AuthorityCommand<SubjectCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SubjectCommand payload,
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
                SubjectAuthority.aggregateId(payload.subjectId()),
                receivedAt);
    }

    private static AuthorityCommand<SubjectCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SubjectCommand payload,
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

    private static AuthorityCommand<SubjectCommand> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            SubjectCommand payload,
            String payloadFingerprint,
            AggregateId aggregateId,
            Instant receivedAt) {
        CommandEnvelope<SubjectCommand> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                new ContractName("subject"),
                new CommandName(commandName(payload)),
                new TraceEnvelope(
                        "trace-subject",
                        "span-subject",
                        Optional.empty(),
                        NOW,
                        "subject-authority-test",
                        new InstanceId("instance-subject-authority-test")),
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

    private static String commandName(SubjectCommand payload) {
        if (payload instanceof RegisterSubject) {
            return "register-subject";
        }
        return "retire-subject";
    }

    private static void assertRejected(
            AuthorityDecision<SubjectState, SubjectReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(SubjectReceiptStatus.REJECTED, decision.response().status());
    }
}
