package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Durable command-journal boundary that the broker-backed command log must preserve.
 */
interface AuthorityCommandJournal {
    /**
     * Validates the backing journal schema or substrate contract.
     */
    void validateSchema();

    /**
     * Append-side journal contract for ingress recorders.
     */
    interface Recorder extends DataAuthority.CommandPort, AuthorityCommandJournal {
    }

    /**
     * Replay-side journal contract for ordered command frames.
     *
     * @param <E> journal entry type
     * @param <R> replay result type
     */
    interface ReplayReader<E extends Entry, R extends Replay> extends AuthorityCommandJournal {
        /**
         * Finds one recorded command frame.
         *
         * @param commandId command id
         * @return entry when present
         */
        Optional<E> find(UUID commandId);

        /**
         * Finds command frames that may be replayed by the current authority owner.
         *
         * @param limit maximum rows to return
         * @return replay candidates
         */
        List<E> findReplayCandidates(int limit);

        /**
         * Replays a command frame through the supplied authority command port.
         *
         * @param commandId command id
         * @param commandPort authority command port
         * @return replay decision and command result
         */
        CompletionStage<R> replay(UUID commandId, DataAuthority.CommandPort commandPort);
    }

    /**
     * Marker for journal terminal states.
     */
    interface JournalStatus {
        /**
         * Stable persisted status name.
         *
         * @return status name
         */
        String name();
    }

    /**
     * Marker for durable replay eligibility verdicts.
     */
    interface ReplayVerdict {
        /**
         * Stable persisted verdict name.
         *
         * @return verdict name
         */
        String name();
    }

    /**
     * Durable command frame and its terminal journal state.
     */
    interface Entry {
        UUID commandId();

        String declarationId();

        String aggregateScope();

        String idempotencyKey();

        String claimedActor();

        String verifiedPrincipal();

        String commandDomain();

        String commandTopic();

        String partitionKey();

        int writerLaneCount();

        int writerLane();

        String writerLaneKeyFingerprint();

        String writerLaneFencingScope();

        long writerClaimEpoch();

        UUID writerClaimId();

        String writerClaimFingerprint();

        JournalStatus status();

        Boolean accepted();

        DataAuthority.RejectionReason rejectionReason();

        long resultRevision();

        String resultMessage();

        DataAuthority.CommandSettlement settlement();

        ReplayVerdict replayEligibility();

        Map<String, Object> guardEvidence();

        String guardEvidenceFingerprint();

        String failureMessage();

        int replayAttempts();

        String payloadHash();

        String commandFingerprint();

        Instant receivedAt();

        Instant completedAt();

        Instant lastReplayedAt();

        Instant updatedAt();

        DataAuthority.AuthorityCommand command();

        boolean replayable();

        boolean routeMatchesCommand();

        boolean laneMatchesCommand();
    }

    /**
     * Result of attempting to submit a journal frame for replay.
     */
    interface Replay {
        UUID commandId();

        boolean submitted();

        String message();

        Entry entry();

        DataAuthority.CommandResult commandResult();
    }
}
