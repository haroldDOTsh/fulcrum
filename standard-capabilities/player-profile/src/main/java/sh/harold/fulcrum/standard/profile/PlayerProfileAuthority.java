package sh.harold.fulcrum.standard.profile;

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
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;
import java.util.Objects;

public final class PlayerProfileAuthority {
    private final AuthorityCommandProcessor<PlayerProfileState, UpsertPlayerProfile, PlayerProfileReceipt> processor;

    public PlayerProfileAuthority(IdempotencyLedger<PlayerProfileState, PlayerProfileReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                PlayerProfileAuthority::rejectionReceipt,
                this::upsert);
    }

    public AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> handle(
            AuthorityCommand<UpsertPlayerProfile> command,
            AuthorityRecord<PlayerProfileState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<PlayerProfileState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, PlayerProfileState.empty());
    }

    public static AggregateId aggregateId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new AggregateId("player-profile:" + subjectId.value());
    }

    public static String cacheKey(SubjectId subjectId) {
        return PlayerProfileContracts.CONTRACT.value() + ":" + aggregateId(subjectId).value();
    }

    private AuthorityMutationResult<PlayerProfileState, PlayerProfileReceipt> upsert(
            AuthorityCommand<UpsertPlayerProfile> command,
            AuthorityRecord<PlayerProfileState> currentRecord) {
        UpsertPlayerProfile payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("player-profile aggregate must be keyed by Subject");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        PlayerProfileSnapshot snapshot = new PlayerProfileSnapshot(
                payload.subjectId(),
                payload.displayName(),
                command.authenticatedPrincipal(),
                payload.observedAt());
        PlayerProfileState state = new PlayerProfileState(snapshot);
        PlayerProfileReceipt receipt = PlayerProfileReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        PlayerProfileUpserted event = new PlayerProfileUpserted(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.subjectId()).value();
        String statePayload = state.wireValue(nextRevision);
        String projectionKey = PlayerProfileContracts.EFFECTIVE_PROJECTION + ":" + snapshot.subjectId().value();
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, projectionKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.subjectId()), statePayload)));
    }

    private static PlayerProfileReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return PlayerProfileReceipt.rejected(reason.name());
    }
}
