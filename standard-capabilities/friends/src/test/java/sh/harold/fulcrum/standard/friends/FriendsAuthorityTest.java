package sh.harold.fulcrum.standard.friends;

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
import sh.harold.fulcrum.standard.contracts.FriendsContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FriendsAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-16T21:30:00Z");
    private static final SubjectId REQUESTER = subject("00000000-0000-0000-0000-000000000911");
    private static final SubjectId ACCEPTER = subject("00000000-0000-0000-0000-000000000912");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-friends-client");

    @Test
    void acceptInviteAdvancesRevisionAndEmitsConnectionProjectionFact() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<FriendsState, FriendsReceipt> decision = authority.handle(
                command("command-friends-1", "friends-idem-1", PRINCIPAL, PRINCIPAL, 5, 0, "payload-1"),
                FriendsAuthority.emptyRecord(5));

        FriendConnectionId connectionId = FriendConnectionId.from(REQUESTER, ACCEPTER);
        assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
        assertEquals(new Revision(1), decision.revision());
        assertEquals(connectionId, decision.state().current().orElseThrow().connectionId());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                decision.emissions().stream().map(emission -> emission.kind()).toList());
        assertEquals(
                FriendsContracts.CONNECTION_PROJECTION + ":" + connectionId.value(),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.PROJECTION)
                        .findFirst()
                        .orElseThrow()
                        .key());
        assertEquals(FriendsAuthority.cacheKey(connectionId),
                decision.emissions().stream()
                        .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                        .findFirst()
                        .orElseThrow()
                        .key());
    }

    @Test
    void duplicateCommandReplaysStoredFriendsDecision() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<AcceptFriendInvite> command = command(
                "command-friends-2",
                "friends-idem-2",
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-2");

        AuthorityDecision<FriendsState, FriendsReceipt> first = authority.handle(command, FriendsAuthority.emptyRecord(5));
        AuthorityDecision<FriendsState, FriendsReceipt> replay = authority.handle(
                command,
                new AuthorityRecord<>(first.revision(), 5, first.state()));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertTrue(replay.replayed());
    }

    @Test
    void revisionMismatchRejectsBeforeFriendsMutation() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<FriendsState, FriendsReceipt> decision = authority.handle(
                command("command-friends-3", "friends-idem-3", PRINCIPAL, PRINCIPAL, 5, 9, "payload-3"),
                FriendsAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(FriendsState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeFriendsMutation() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<FriendsState, FriendsReceipt> decision = authority.handle(
                command(
                        "command-friends-4",
                        "friends-idem-4",
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        0,
                        "payload-4"),
                FriendsAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void staleFencingEpochRejectsBeforeFriendsMutation() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<FriendsState, FriendsReceipt> decision = authority.handle(
                command("command-friends-5", "friends-idem-5", PRINCIPAL, PRINCIPAL, 4, 0, "payload-5"),
                FriendsAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.STALE_FENCING_EPOCH);
    }

    @Test
    void aggregateMustBeKeyedByCanonicalSubjectPair() {
        FriendsAuthority authority = new FriendsAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<AcceptFriendInvite> command = command(
                "command-friends-6",
                "friends-idem-6",
                PRINCIPAL,
                PRINCIPAL,
                5,
                0,
                "payload-6",
                new AggregateId("friends:wrong-pair"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, FriendsAuthority.emptyRecord(5)));
    }

    private static AuthorityCommand<AcceptFriendInvite> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payloadFingerprint,
                FriendsAuthority.aggregateId(REQUESTER, ACCEPTER));
    }

    private static AuthorityCommand<AcceptFriendInvite> command(
            String commandId,
            String idempotencyKey,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint,
            AggregateId aggregateId) {
        AcceptFriendInvite payload = new AcceptFriendInvite(REQUESTER, ACCEPTER, NOW, expectedRevision);
        CommandEnvelope<AcceptFriendInvite> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                FriendsContracts.CONTRACT,
                new CommandName("accept-friend-invite"),
                new TraceEnvelope(
                        "trace-friends-1",
                        "span-friends-1",
                        Optional.empty(),
                        NOW,
                        "standard-friends-test",
                        new InstanceId("instance-friends-test")),
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
            AuthorityDecision<FriendsState, FriendsReceipt> decision,
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
