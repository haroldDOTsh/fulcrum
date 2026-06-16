package sh.harold.fulcrum.standard.stats;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.StatsContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StatsAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000001301"));
    private static final ExperienceId EXPERIENCE = new ExperienceId("experience.stats.suite");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-stats-client");
    private static final String STAT_KEY = "session-completions";

    @Test
    void recordStatDeltaAdvancesRevisionAndEmitsCounterExperienceAndLedgerProjections() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<StatsState, StatsReceipt> decision = authority.handle(
                command("command-stats-1", "stats-idem-1", SUBJECT, EXPERIENCE, STAT_KEY, 1, PRINCIPAL, PRINCIPAL, 5, 0, "payload-1"),
                StatsAuthority.emptyRecord(5));

        StatsCounterId counterId = StatsAuthority.counterId(SUBJECT, STAT_KEY);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(1, decision.state().total());
        assertEquals(1, decision.state().ledgerEntries().size());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(1, decision.emissions().stream()
                .filter(emission -> emission.key().equals(StatsContracts.COUNTER_PROJECTION + ":" + counterId.value()))
                .count());
        assertEquals(1, decision.emissions().stream()
                .filter(emission -> emission.key().equals(StatsContracts.LEDGER_PROJECTION + ":stats-idem-1"))
                .count());
        assertEquals(StatsAuthority.cacheKey(counterId),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredStatsDecisionWithoutSecondLedgerEntry() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<RecordStatDelta> command = command(
                "command-stats-2",
                "stats-idem-2",
                SUBJECT,
                EXPERIENCE,
                STAT_KEY,
                1,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-2");

        AuthorityDecision<StatsState, StatsReceipt> first = authority.handle(command, StatsAuthority.emptyRecord(5));
        AuthorityDecision<StatsState, StatsReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertEquals(1, replay.state().ledgerEntries().size());
        assertTrue(replay.replayed());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedBeforeStatsMutation() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<RecordStatDelta> firstCommand = command(
                "command-stats-3a",
                "stats-idem-3",
                SUBJECT,
                EXPERIENCE,
                STAT_KEY,
                1,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-3a");
        AuthorityDecision<StatsState, StatsReceipt> first = authority.handle(firstCommand, StatsAuthority.emptyRecord(5));

        AuthorityDecision<StatsState, StatsReceipt> conflict = authority.handle(
                command(
                        "command-stats-3b",
                        "stats-idem-3",
                        SUBJECT,
                        EXPERIENCE,
                        STAT_KEY,
                        2,
                        PRINCIPAL,
                        PRINCIPAL,
                        5,
                        1,
                        "payload-3b"),
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertRejected(conflict, AuthorityRejectionReason.IDEMPOTENCY_CONFLICT);
        assertEquals(first.state(), conflict.state());
    }

    @Test
    void revisionMismatchRejectsBeforeStatsMutation() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<StatsState, StatsReceipt> decision = authority.handle(
                command("command-stats-4", "stats-idem-4", SUBJECT, EXPERIENCE, STAT_KEY, 1, PRINCIPAL, PRINCIPAL, 5, 9, "payload-4"),
                StatsAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(StatsState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeStatsMutation() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<StatsState, StatsReceipt> decision = authority.handle(
                command(
                        "command-stats-5",
                        "stats-idem-5",
                        SUBJECT,
                        EXPERIENCE,
                        STAT_KEY,
                        1,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "payload-5"),
                StatsAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void aggregateMustBeKeyedBySubjectAndStatKey() {
        StatsAuthority authority = new StatsAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<RecordStatDelta> command = command(
                "command-stats-6",
                "stats-idem-6",
                SUBJECT,
                EXPERIENCE,
                STAT_KEY,
                1,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-6",
                new AggregateId("stats:wrong-counter"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, StatsAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<RecordStatDelta> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            ExperienceId experienceId,
            String statKey,
            long delta,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                subjectId,
                experienceId,
                statKey,
                delta,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                StatsAuthority.aggregateId(StatsAuthority.counterId(subjectId, statKey)));
    }

    private static AuthorityCommand<RecordStatDelta> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            ExperienceId experienceId,
            String statKey,
            long delta,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint,
            AggregateId aggregateId) {
        RecordStatDelta payload = new RecordStatDelta(
                subjectId,
                experienceId,
                statKey,
                delta,
                NOW,
                expectedRevision);
        CommandEnvelope<RecordStatDelta> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                StatsContracts.CONTRACT,
                new CommandName("record-stat-delta"),
                new TraceEnvelope(
                        "trace-stats-1",
                        "span-stats-1",
                        Optional.empty(),
                        NOW,
                        "standard-stats-test",
                        new InstanceId("instance-stats-test")),
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
            AuthorityDecision<StatsState, StatsReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }
}
