package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuthorityDecision<S, R>(
        AuthorityDecisionStatus status,
        Optional<AuthorityRejectionReason> rejectionReason,
        Revision revision,
        S state,
        R response,
        List<AuthorityEmission> emissions,
        TraceEnvelope traceEnvelope,
        boolean replayed) {
    public AuthorityDecision {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        revision = Objects.requireNonNull(revision, "revision");
        response = Objects.requireNonNull(response, "response");
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        if (status == AuthorityDecisionStatus.ACCEPTED && rejectionReason.isPresent()) {
            throw new IllegalArgumentException("accepted decision cannot have a rejection reason");
        }
        if (status == AuthorityDecisionStatus.REJECTED && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected decision requires a rejection reason");
        }
    }

    public static <S, R> AuthorityDecision<S, R> accepted(
            Revision revision,
            S state,
            R response,
            List<AuthorityEmission> emissions,
            TraceEnvelope traceEnvelope) {
        return new AuthorityDecision<>(
                AuthorityDecisionStatus.ACCEPTED,
                Optional.empty(),
                revision,
                state,
                response,
                emissions,
                traceEnvelope,
                false);
    }

    public static <S, R> AuthorityDecision<S, R> rejected(
            AuthorityRejectionReason reason,
            Revision revision,
            S state,
            R response,
            TraceEnvelope traceEnvelope) {
        return new AuthorityDecision<>(
                AuthorityDecisionStatus.REJECTED,
                Optional.of(reason),
                revision,
                state,
                response,
                List.of(),
                traceEnvelope,
                false);
    }

    AuthorityDecision<S, R> asReplay() {
        return new AuthorityDecision<>(status, rejectionReason, revision, state, response, emissions, traceEnvelope, true);
    }
}
