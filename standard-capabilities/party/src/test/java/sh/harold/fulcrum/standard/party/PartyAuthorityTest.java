package sh.harold.fulcrum.standard.party;

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
import sh.harold.fulcrum.standard.contracts.PartyContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PartyAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T20:30:00Z");
    private static final PartyId PARTY_ID = new PartyId("party-authority-1");
    private static final SubjectId LEADER = subject("00000000-0000-0000-0000-000000000811");
    private static final SubjectId MEMBER = subject("00000000-0000-0000-0000-000000000812");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-party-client");

    @Test
    void formPartyAdvancesRevisionAndEmitsRosterProjectionFact() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PartyState, PartyReceipt> decision = authority.handle(
                command("command-party-1", "party-idem-1", PARTY_ID, PRINCIPAL, PRINCIPAL, 5, 0, "payload-1"),
                PartyAuthority.emptyRecord(5));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(PARTY_ID, decision.state().current().orElseThrow().partyId());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                PartyContracts.ROSTER_PROJECTION + ":" + PARTY_ID.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(PartyAuthority.cacheKey(PARTY_ID),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredPartyDecision() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<FormParty> command = command(
                "command-party-2",
                "party-idem-2",
                PARTY_ID,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-2");

        AuthorityDecision<PartyState, PartyReceipt> first = authority.handle(command, PartyAuthority.emptyRecord(5));
        AuthorityDecision<PartyState, PartyReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforePartyMutation() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PartyState, PartyReceipt> decision = authority.handle(
                command("command-party-3", "party-idem-3", PARTY_ID, PRINCIPAL, PRINCIPAL, 5, 9, "payload-3"),
                PartyAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(PartyState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforePartyMutation() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PartyState, PartyReceipt> decision = authority.handle(
                command(
                        "command-party-4",
                        "party-idem-4",
                        PARTY_ID,
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "payload-4"),
                PartyAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforePartyMutation() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<PartyState, PartyReceipt> decision = authority.handle(
                command("command-party-5", "party-idem-5", PARTY_ID, PRINCIPAL, PRINCIPAL, 4, 0, "payload-5"),
                PartyAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedByParty() {
        PartyAuthority authority = new PartyAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<FormParty> command = command(
                "command-party-6",
                "party-idem-6",
                PARTY_ID,
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-6",
                new AggregateId("party:wrong-party"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, PartyAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<FormParty> command(
            String commandId,
            String idempotencyKey,
            PartyId partyId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                partyId,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                PartyAuthority.aggregateId(partyId));
    }

    private static AuthorityCommand<FormParty> command(
            String commandId,
            String idempotencyKey,
            PartyId partyId,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint,
            AggregateId aggregateId) {
        FormParty payload = new FormParty(partyId, LEADER, List.of(LEADER, MEMBER), NOW, expectedRevision);
        CommandEnvelope<FormParty> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                PartyContracts.CONTRACT,
                new CommandName("form-party"),
                new TraceEnvelope(
                        "trace-party-1",
                        "span-party-1",
                        Optional.empty(),
                        NOW,
                        "standard-party-test",
                        new InstanceId("instance-party-test")),
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
            AuthorityDecision<PartyState, PartyReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
