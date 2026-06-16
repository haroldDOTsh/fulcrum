package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.FriendsContracts;

import java.util.List;
import java.util.Objects;

public final class FriendsAuthority {
    private final AuthorityCommandProcessor<FriendsState, AcceptFriendInvite, FriendsReceipt> processor;

    public FriendsAuthority(IdempotencyLedger<FriendsState, FriendsReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                FriendsAuthority::rejectionReceipt,
                this::accept);
    }

    public AuthorityDecision<FriendsState, FriendsReceipt> handle(
            AuthorityCommand<AcceptFriendInvite> command,
            AuthorityRecord<FriendsState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<FriendsState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, FriendsState.empty());
    }

    public static AggregateId aggregateId(SubjectId first, SubjectId second) {
        return aggregateId(FriendConnectionId.from(first, second));
    }

    public static AggregateId aggregateId(FriendConnectionId connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        return new AggregateId("friends:" + connectionId.value());
    }

    public static String cacheKey(FriendConnectionId connectionId) {
        return FriendsContracts.CONTRACT.value() + ":" + aggregateId(connectionId).value();
    }

    private AuthorityMutationResult<FriendsState, FriendsReceipt> accept(
            AuthorityCommand<AcceptFriendInvite> command,
            AuthorityRecord<FriendsState> currentRecord) {
        AcceptFriendInvite payload = command.envelope().payload();
        FriendConnectionId connectionId = payload.connectionId();
        if (!command.envelope().aggregateId().equals(aggregateId(connectionId))) {
            throw new IllegalArgumentException("friends aggregate must be keyed by canonical Subject pair");
        }
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalArgumentException("friend connection already exists for aggregate");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        FriendConnectionSnapshot snapshot = FriendConnectionSnapshot.accepted(
                payload.requesterSubjectId(),
                payload.accepterSubjectId(),
                command.authenticatedPrincipal(),
                payload.acceptedAt());
        FriendsState state = new FriendsState(snapshot);
        FriendsReceipt receipt = FriendsReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        FriendInviteAccepted event = new FriendInviteAccepted(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.connectionId()).value();
        String statePayload = state.wireValue(nextRevision);
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, FriendsContracts.CONNECTION_PROJECTION + ":" + snapshot.connectionId().value(), statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.connectionId()), statePayload)));
    }

    private static FriendsReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return FriendsReceipt.rejected(reason.name());
    }
}
