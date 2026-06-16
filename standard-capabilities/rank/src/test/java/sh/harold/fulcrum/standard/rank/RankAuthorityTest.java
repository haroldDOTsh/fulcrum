package sh.harold.fulcrum.standard.rank;

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
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RankAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T14:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000202"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-rank-client");

    @Test
    void grantRankAdvancesRevisionAndEmitsOneEffectiveProjectionFact() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<RankState, RankReceipt> decision = authority.handle(
                command("command-rank-1", "rank-idem-1", SUBJECT, PRINCIPAL, PRINCIPAL, 5, 0, "admin", "payload-1"),
                RankAuthority.emptyRecord(5));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals("admin", decision.state().current().orElseThrow().primaryRankKey());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(1, decision.emissions().stream()
                .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                .count());
        assertEquals(
                RankContracts.EFFECTIVE_PROJECTION + ":" + SUBJECT.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(RankAuthority.cacheKey(SUBJECT),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredRankDecision() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<GrantRank> command = command(
                "command-rank-2",
                "rank-idem-2",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "admin",
                "payload-2");

        AuthorityDecision<RankState, RankReceipt> first = authority.handle(command, RankAuthority.emptyRecord(5));
        AuthorityDecision<RankState, RankReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforeRankMutation() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<RankState, RankReceipt> decision = authority.handle(
                command("command-rank-3", "rank-idem-3", SUBJECT, PRINCIPAL, PRINCIPAL, 5, 9, "admin", "payload-3"),
                RankAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(RankState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeRankMutation() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<RankState, RankReceipt> decision = authority.handle(
                command(
                        "command-rank-4",
                        "rank-idem-4",
                        SUBJECT,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "admin",
                        "payload-4"),
                RankAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforeRankMutation() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<RankState, RankReceipt> decision = authority.handle(
                command("command-rank-5", "rank-idem-5", SUBJECT, PRINCIPAL, PRINCIPAL, 4, 0, "admin", "payload-5"),
                RankAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedBySubject() {
        RankAuthority authority = new RankAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<GrantRank> command = command(
                "command-rank-6",
                "rank-idem-6",
                SUBJECT,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "admin",
                "payload-6",
                new AggregateId("rank:wrong-subject"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, RankAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<GrantRank> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String rankKey,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                subjectId,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                rankKey,
                payloadFingerprint,
                RankAuthority.aggregateId(subjectId));
    }

    private static AuthorityCommand<GrantRank> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String rankKey,
            String payloadFingerprint,
            AggregateId aggregateId) {
        GrantRank payload = new GrantRank(subjectId, rankKey, NOW, expectedRevision);
        CommandEnvelope<GrantRank> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                RankContracts.CONTRACT,
                new CommandName("grant-rank"),
                new TraceEnvelope(
                        "trace-rank-1",
                        "span-rank-1",
                        Optional.empty(),
                        NOW,
                        "standard-rank-test",
                        new InstanceId("instance-rank-test")),
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
            AuthorityDecision<RankState, RankReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }
}
