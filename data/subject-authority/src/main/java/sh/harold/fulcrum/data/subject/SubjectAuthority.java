package sh.harold.fulcrum.data.subject;

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

import java.util.List;
import java.util.Objects;

public final class SubjectAuthority {
    private static final String CONTRACT_NAME = "subject";

    private final AuthorityCommandProcessor<SubjectState, SubjectCommand, SubjectReceipt> processor;

    public SubjectAuthority(IdempotencyLedger<SubjectState, SubjectReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                SubjectAuthority::rejectionReceipt,
                this::apply);
    }

    public AuthorityDecision<SubjectState, SubjectReceipt> handle(
            AuthorityCommand<SubjectCommand> command,
            AuthorityRecord<SubjectState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<SubjectState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, SubjectState.empty());
    }

    public static AggregateId aggregateId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new AggregateId("subject:" + subjectId.value());
    }

    public static String cacheKey(SubjectId subjectId) {
        return CONTRACT_NAME + ":" + aggregateId(subjectId).value();
    }

    private AuthorityMutationResult<SubjectState, SubjectReceipt> apply(
            AuthorityCommand<SubjectCommand> command,
            AuthorityRecord<SubjectState> currentRecord) {
        SubjectCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("subject aggregate must be keyed by Subject");
        }

        if (payload instanceof RegisterSubject register) {
            return register(command, currentRecord, register);
        }
        if (payload instanceof RetireSubject retire) {
            return retire(command, currentRecord, retire);
        }
        throw new IllegalArgumentException("unknown Subject command");
    }

    private AuthorityMutationResult<SubjectState, SubjectReceipt> register(
            AuthorityCommand<SubjectCommand> command,
            AuthorityRecord<SubjectState> currentRecord,
            RegisterSubject payload) {
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalStateException("Subject identity is immutable once registered");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SubjectChangeKind.REGISTERED, SubjectSnapshot.from(payload, command.authenticatedPrincipal()));
    }

    private AuthorityMutationResult<SubjectState, SubjectReceipt> retire(
            AuthorityCommand<SubjectCommand> command,
            AuthorityRecord<SubjectState> currentRecord,
            RetireSubject payload) {
        SubjectSnapshot current = currentRecord.state().current()
                .orElseThrow(() -> new IllegalStateException("Subject must be registered before retirement"));
        if (!current.subjectId().equals(payload.subjectId())) {
            throw new IllegalArgumentException("subject command id must match current aggregate");
        }
        if (current.status() != SubjectLifecycleStatus.ACTIVE) {
            throw new IllegalStateException("retired Subject cannot be mutated");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SubjectChangeKind.RETIRED, current.retire(payload, command.authenticatedPrincipal()));
    }

    private AuthorityMutationResult<SubjectState, SubjectReceipt> accepted(
            AuthorityCommand<SubjectCommand> command,
            Revision revision,
            SubjectChangeKind changeKind,
            SubjectSnapshot snapshot) {
        SubjectState state = new SubjectState(snapshot);
        SubjectReceipt receipt = SubjectReceipt.accepted(
                snapshot,
                revision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String aggregateKey = aggregateId(snapshot.subjectId()).value();
        return new AuthorityMutationResult<>(
                revision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, SubjectChanged.from(changeKind, snapshot, revision).wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(revision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.subjectId()), state.wireValue(revision))));
    }

    private static SubjectReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return SubjectReceipt.rejected(reason.name());
    }
}
