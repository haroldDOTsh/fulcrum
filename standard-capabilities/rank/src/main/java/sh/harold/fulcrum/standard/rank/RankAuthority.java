package sh.harold.fulcrum.standard.rank;

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
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.util.List;
import java.util.Objects;

public final class RankAuthority {
    private final AuthorityCommandProcessor<RankState, GrantRank, RankReceipt> processor;

    public RankAuthority(IdempotencyLedger<RankState, RankReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                RankAuthority::rejectionReceipt,
                this::grant);
    }

    public AuthorityDecision<RankState, RankReceipt> handle(
            AuthorityCommand<GrantRank> command,
            AuthorityRecord<RankState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<RankState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, RankState.empty());
    }

    public static AggregateId aggregateId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new AggregateId("rank:" + subjectId.value());
    }

    public static String cacheKey(SubjectId subjectId) {
        return RankContracts.CONTRACT.value() + ":" + aggregateId(subjectId).value();
    }

    private AuthorityMutationResult<RankState, RankReceipt> grant(
            AuthorityCommand<GrantRank> command,
            AuthorityRecord<RankState> currentRecord) {
        GrantRank payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("rank aggregate must be keyed by Subject");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        EffectiveRankSnapshot snapshot = new EffectiveRankSnapshot(
                payload.subjectId(),
                payload.rankKey(),
                permissionsFor(payload.rankKey()),
                command.authenticatedPrincipal(),
                payload.grantedAt());
        RankState state = new RankState(snapshot);
        RankReceipt receipt = RankReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        RankGranted event = new RankGranted(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.subjectId()).value();
        String statePayload = state.wireValue(nextRevision);
        String projectionKey = RankContracts.EFFECTIVE_PROJECTION + ":" + snapshot.subjectId().value();
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

    private static String permissionsFor(String rankKey) {
        return "rank:" + rankKey;
    }

    private static RankReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return RankReceipt.rejected(reason.name());
    }
}
