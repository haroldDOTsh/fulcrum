package sh.harold.fulcrum.standard.punishment;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
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
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PunishmentAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T16:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000501"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-punishment-client");

    @Test
    void issuePunishmentAdvancesRevisionAndFeedsLoginGateProjection() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PunishmentState, PunishmentReceipt> decision = authority.handle(
                command("command-punishment-1", "punishment-idem-1", SUBJECT, PRINCIPAL, PRINCIPAL, 9, 0, "punishment=1", "ban\nevasion", "payload-1"),
                PunishmentAuthority.emptyRecord(9));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals("ban\nevasion", decision.state().active().orElseThrow().reason());
        assertFalse(PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW.plusSeconds(30)),
                decision.state().active()).allowed());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                PunishmentContracts.ACTIVE_PROJECTION + ":" + SUBJECT.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(PunishmentAuthority.cacheKey(SUBJECT),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
        PunishmentState cachedState = PunishmentState.parse(decision.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                .findFirst()
                .orElseThrow()
                .payload());
        assertEquals(decision.state(), cachedState);
        assertEquals("punishment=1", cachedState.active().orElseThrow().punishmentId());
    }

    @Test
    void duplicateCommandReplaysStoredPunishmentDecision() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<IssuePunishment> command = command(
                "command-punishment-2",
                "punishment-idem-2",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                9,
                0,
                "punishment-2",
                "chargeback fraud",
                "payload-2");

        AuthorityDecision<PunishmentState, PunishmentReceipt> first = authority.handle(command, PunishmentAuthority.emptyRecord(9));
        AuthorityDecision<PunishmentState, PunishmentReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 9, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforePunishmentMutation() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PunishmentState, PunishmentReceipt> decision = authority.handle(
                command("command-punishment-3", "punishment-idem-3", SUBJECT, PRINCIPAL, PRINCIPAL, 9, 4, "punishment-3", "ban evasion", "payload-3"),
                PunishmentAuthority.emptyRecord(9));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(PunishmentState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforePunishmentMutation() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PunishmentState, PunishmentReceipt> decision = authority.handle(
                command(
                        "command-punishment-4",
                        "punishment-idem-4",
                        SUBJECT,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        9,
                        0,
                        "punishment-4",
                        "ban evasion",
                        "payload-4"),
                PunishmentAuthority.emptyRecord(9));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforePunishmentMutation() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PunishmentState, PunishmentReceipt> decision = authority.handle(
                command("command-punishment-5", "punishment-idem-5", SUBJECT, PRINCIPAL, PRINCIPAL, 8, 0, "punishment-5", "ban evasion", "payload-5"),
                PunishmentAuthority.emptyRecord(9));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedBySubject() {
        PunishmentAuthority authority = new PunishmentAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<IssuePunishment> command = command(
                "command-punishment-6",
                "punishment-idem-6",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                9,
                0,
                "punishment-6",
                "ban evasion",
                "payload-6",
                new AggregateId("punishment:wrong-subject"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, PunishmentAuthority.emptyRecord(9)));
    }

    private static AuthorityCommand<IssuePunishment> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String punishmentId,
            String reason,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                subjectId,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                punishmentId,
                reason,
                payloadFingerprint,
                PunishmentAuthority.aggregateId(subjectId));
    }

    private static AuthorityCommand<IssuePunishment> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String punishmentId,
            String reason,
            String payloadFingerprint,
            AggregateId aggregateId) {
        IssuePunishment payload = new IssuePunishment(
                subjectId,
                punishmentId,
                reason,
                NOW,
                NOW.plusSeconds(3600),
                expectedRevision);
        CommandEnvelope<IssuePunishment> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                PunishmentContracts.CONTRACT,
                new CommandName("issue-punishment"),
                new TraceEnvelope(
                        "trace-punishment-1",
                        "span-punishment-1",
                        Optional.empty(),
                        NOW,
                        "standard-punishment-test",
                        new InstanceId("instance-punishment-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                Optional.of(new Revision(expectedRevision)),
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(
            AuthorityDecision<PunishmentState, PunishmentReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }
}
