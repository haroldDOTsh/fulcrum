package sh.harold.fulcrum.data.authority;

import java.util.Objects;
import java.util.Optional;

public final class CommandReadConsistencyVerifier {
    public <S, R, T> CommandReadConsistencyReceipt<T> verify(
            AuthorityDecision<S, R> decision,
            CommandReadContract<T> contract) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(contract, "contract");

        if (decision.status() != AuthorityDecisionStatus.ACCEPTED) {
            return invalid(decision, contract, CommandReadConsistencyViolation.REJECTED_DECISION_HAS_NO_POST_WRITE_STATE);
        }
        if (contract.consistency() == CommandReadConsistency.ASYNC_EVENTUAL) {
            return new CommandReadConsistencyReceipt<>(
                    contract.consistency(),
                    decision.revision(),
                    Optional.empty(),
                    Optional.empty(),
                    false);
        }

        ProjectionSnapshot<T> projection = contract.postWriteProjection().orElseThrow();
        if (!projection.revision().equals(decision.revision())) {
            return invalid(decision, contract, CommandReadConsistencyViolation.SYNC_PROJECTION_REVISION_MISMATCH);
        }
        if (!hasEmission(decision, AuthorityEmissionKind.PROJECTION, projection.aggregateId())) {
            return invalid(decision, contract, CommandReadConsistencyViolation.SYNC_PROJECTION_EMISSION_MISSING);
        }
        if (!hasEmission(decision, AuthorityEmissionKind.CACHE_WRITE, projection.aggregateId())) {
            return invalid(decision, contract, CommandReadConsistencyViolation.SYNC_CACHE_WRITE_MISSING);
        }
        return new CommandReadConsistencyReceipt<>(
                contract.consistency(),
                decision.revision(),
                contract.postWriteProjection(),
                Optional.empty(),
                true);
    }

    private static <S, R, T> CommandReadConsistencyReceipt<T> invalid(
            AuthorityDecision<S, R> decision,
            CommandReadContract<T> contract,
            CommandReadConsistencyViolation violation) {
        return new CommandReadConsistencyReceipt<>(
                contract.consistency(),
                decision.revision(),
                contract.postWriteProjection(),
                Optional.of(violation),
                false);
    }

    private static boolean hasEmission(
            AuthorityDecision<?, ?> decision,
            AuthorityEmissionKind kind,
            String aggregateId) {
        return decision.emissions().stream()
                .anyMatch(emission -> emission.kind() == kind && emission.key().equals(aggregateId));
    }
}
