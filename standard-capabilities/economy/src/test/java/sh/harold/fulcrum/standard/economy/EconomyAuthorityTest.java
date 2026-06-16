package sh.harold.fulcrum.standard.economy;

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
import sh.harold.fulcrum.standard.contracts.EconomyContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EconomyAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T23:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000001101"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-economy-client");
    private static final String CURRENCY = "coins";

    @Test
    void postLedgerEntryAdvancesRevisionAndEmitsBalanceAndAuditProjections() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<EconomyState, EconomyReceipt> decision = authority.handle(
                command("command-economy-1", "economy-idem-1", SUBJECT, CURRENCY, 100, PRINCIPAL, PRINCIPAL, 5, 0, "payload-1"),
                EconomyAuthority.emptyRecord(5));

        EconomyAccountId accountId = EconomyAuthority.accountId(SUBJECT, CURRENCY);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(100, decision.state().balanceMinorUnits());
        assertEquals(1, decision.state().ledgerEntries().size());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(1, decision.emissions().stream()
                .filter(emission -> emission.key().equals(EconomyContracts.BALANCE_PROJECTION + ":" + accountId.value()))
                .count());
        assertEquals(1, decision.emissions().stream()
                .filter(emission -> emission.key().equals(EconomyContracts.LEDGER_PROJECTION + ":economy-idem-1"))
                .count());
        assertEquals(EconomyAuthority.cacheKey(accountId),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredEconomyDecisionWithoutSecondLedgerEntry() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<PostLedgerEntry> command = command(
                "command-economy-2",
                "economy-idem-2",
                SUBJECT,
                CURRENCY,
                100,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-2");

        AuthorityDecision<EconomyState, EconomyReceipt> first = authority.handle(command, EconomyAuthority.emptyRecord(5));
        AuthorityDecision<EconomyState, EconomyReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertEquals(1, replay.state().ledgerEntries().size());
        assertTrue(replay.replayed());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedBeforeLedgerMutation() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<PostLedgerEntry> firstCommand = command(
                "command-economy-3a",
                "economy-idem-3",
                SUBJECT,
                CURRENCY,
                100,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-3a");
        AuthorityDecision<EconomyState, EconomyReceipt> first = authority.handle(firstCommand, EconomyAuthority.emptyRecord(5));

        AuthorityDecision<EconomyState, EconomyReceipt> conflict = authority.handle(
                command(
                        "command-economy-3b",
                        "economy-idem-3",
                        SUBJECT,
                        CURRENCY,
                        200,
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
    void revisionMismatchRejectsBeforeEconomyMutation() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<EconomyState, EconomyReceipt> decision = authority.handle(
                command("command-economy-4", "economy-idem-4", SUBJECT, CURRENCY, 100, PRINCIPAL, PRINCIPAL, 5, 9, "payload-4"),
                EconomyAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(EconomyState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeEconomyMutation() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<EconomyState, EconomyReceipt> decision = authority.handle(
                command(
                        "command-economy-5",
                        "economy-idem-5",
                        SUBJECT,
                        CURRENCY,
                        100,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "payload-5"),
                EconomyAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void aggregateMustBeKeyedBySubjectAndCurrency() {
        EconomyAuthority authority = new EconomyAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<PostLedgerEntry> command = command(
                "command-economy-6",
                "economy-idem-6",
                SUBJECT,
                CURRENCY,
                100,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-6",
                new AggregateId("economy:wrong-account"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, EconomyAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<PostLedgerEntry> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            String currencyKey,
            long deltaMinorUnits,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                subjectId,
                currencyKey,
                deltaMinorUnits,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                EconomyAuthority.aggregateId(EconomyAuthority.accountId(subjectId, currencyKey)));
    }

    private static AuthorityCommand<PostLedgerEntry> command(
            String commandId,
            String idempotencyKey,
            SubjectId subjectId,
            String currencyKey,
            long deltaMinorUnits,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint,
            AggregateId aggregateId) {
        PostLedgerEntry payload = new PostLedgerEntry(
                subjectId,
                currencyKey,
                deltaMinorUnits,
                "suite-credit",
                NOW,
                expectedRevision);
        CommandEnvelope<PostLedgerEntry> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                EconomyContracts.CONTRACT,
                new CommandName("post-ledger-entry"),
                new TraceEnvelope(
                        "trace-economy-1",
                        "span-economy-1",
                        Optional.empty(),
                        NOW,
                        "standard-economy-test",
                        new InstanceId("instance-economy-test")),
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
            AuthorityDecision<EconomyState, EconomyReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }
}
