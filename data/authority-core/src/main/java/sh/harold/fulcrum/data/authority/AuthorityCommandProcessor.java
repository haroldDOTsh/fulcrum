package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.CommandPayload;

import java.util.Objects;
import java.util.Optional;

public final class AuthorityCommandProcessor<S, C extends CommandPayload, R> {
    private final IdempotencyLedger<S, R> idempotencyLedger;
    private final AuthorityRejectionMapper<R> rejectionMapper;
    private final AuthorityMutation<S, C, R> mutation;

    public AuthorityCommandProcessor(
            IdempotencyLedger<S, R> idempotencyLedger,
            AuthorityRejectionMapper<R> rejectionMapper,
            AuthorityMutation<S, C, R> mutation) {
        this.idempotencyLedger = Objects.requireNonNull(idempotencyLedger, "idempotencyLedger");
        this.rejectionMapper = Objects.requireNonNull(rejectionMapper, "rejectionMapper");
        this.mutation = Objects.requireNonNull(mutation, "mutation");
    }

    public AuthorityDecision<S, R> process(AuthorityCommand<C> command, AuthorityRecord<S> currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<StoredAuthorityDecision<S, R>> stored = idempotencyLedger.find(command.envelope().idempotencyKey());
        if (stored.isPresent()) {
            StoredAuthorityDecision<S, R> decision = stored.orElseThrow();
            if (decision.payloadFingerprint().equals(command.payloadFingerprint())) {
                return decision.decision().asReplay();
            }
            return reject(command, currentRecord, AuthorityRejectionReason.IDEMPOTENCY_CONFLICT, false);
        }

        Optional<AuthorityRejectionReason> rejection = firstRejection(command, currentRecord);
        if (rejection.isPresent()) {
            return reject(command, currentRecord, rejection.orElseThrow(), true);
        }

        AuthorityMutationResult<S, R> result = mutation.apply(command, currentRecord);
        if (result.revision().value() <= currentRecord.revision().value()) {
            throw new IllegalStateException("accepted authority mutation must advance revision");
        }
        AuthorityDecision<S, R> decision = AuthorityDecision.accepted(
                result.revision(),
                result.state(),
                result.response(),
                result.emissions(),
                command.envelope().traceEnvelope());
        idempotencyLedger.store(command.envelope().idempotencyKey(), command.payloadFingerprint(), decision);
        return decision;
    }

    private Optional<AuthorityRejectionReason> firstRejection(AuthorityCommand<C> command, AuthorityRecord<S> currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(AuthorityRejectionReason.DEADLINE_EXPIRED);
        }
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(AuthorityRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(AuthorityRejectionReason.STALE_FENCING_EPOCH);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(AuthorityRejectionReason.REVISION_MISMATCH);
        }
        return Optional.empty();
    }

    private AuthorityDecision<S, R> reject(
            AuthorityCommand<C> command,
            AuthorityRecord<S> currentRecord,
            AuthorityRejectionReason reason,
            boolean storeForReplay) {
        AuthorityDecision<S, R> decision = AuthorityDecision.rejected(
                reason,
                currentRecord.revision(),
                currentRecord.state(),
                rejectionMapper.map(reason),
                command.envelope().traceEnvelope());
        if (storeForReplay) {
            idempotencyLedger.store(command.envelope().idempotencyKey(), command.payloadFingerprint(), decision);
        }
        return decision;
    }
}
