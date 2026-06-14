package sh.harold.fulcrum.api.data.impl.authority.events;

import java.util.UUID;

/**
 * Outcome returned by a projection or dispatcher target after observing an authority event.
 */
public final class AuthorityEventDispatchResult {
    private static final String DEFAULT_PROJECTION_VERSION = "unversioned";

    /**
     * Durable dispatch outcome used for cursor, retry, and quarantine bookkeeping.
     */
    public enum Status {
        /**
         * The consumer handled the event and its cursor may advance.
         */
        SUCCESS,

        /**
         * The consumer should see the same event again after the retry delay.
         */
        RETRY,

        /**
         * The event is blocked for this consumer until an operator repairs it.
         */
        QUARANTINED
    }

    private static final AuthorityEventDispatchResult SUCCESS =
        new AuthorityEventDispatchResult(Status.SUCCESS, "", DEFAULT_PROJECTION_VERSION, "", null);

    private final Status status;
    private final String message;
    private final String projectionVersion;
    private final String outputFingerprint;
    private final UUID replayBatchId;

    private AuthorityEventDispatchResult(
        Status status,
        String message,
        String projectionVersion,
        String outputFingerprint,
        UUID replayBatchId
    ) {
        this.status = status;
        this.message = message == null ? "" : message;
        this.projectionVersion = projectionVersion == null || projectionVersion.isBlank()
            ? DEFAULT_PROJECTION_VERSION
            : projectionVersion.trim();
        this.outputFingerprint = outputFingerprint == null ? "" : outputFingerprint.trim();
        this.replayBatchId = replayBatchId;
    }

    /**
     * Creates a successful dispatch result.
     *
     * @return shared success result
     */
    public static AuthorityEventDispatchResult success() {
        return SUCCESS;
    }

    /**
     * Creates a successful dispatch result with projection lineage metadata.
     *
     * @param projectionVersion stable reducer or projection implementation version
     * @param outputFingerprint digest of the materialized output after applying the event
     * @return success result
     */
    public static AuthorityEventDispatchResult success(String projectionVersion, String outputFingerprint) {
        return SUCCESS.withProjection(projectionVersion, outputFingerprint);
    }

    /**
     * Creates a retryable dispatch failure.
     *
     * @param message diagnostic failure message
     * @return retry result
     */
    public static AuthorityEventDispatchResult retry(String message) {
        return new AuthorityEventDispatchResult(Status.RETRY, message, DEFAULT_PROJECTION_VERSION, "", null);
    }

    /**
     * Creates a quarantined dispatch failure.
     *
     * @param message diagnostic failure message
     * @return quarantine result
     */
    public static AuthorityEventDispatchResult quarantine(String message) {
        return new AuthorityEventDispatchResult(Status.QUARANTINED, message, DEFAULT_PROJECTION_VERSION, "", null);
    }

    /**
     * Returns a copy of this result with projection lineage metadata.
     *
     * @param projectionVersion stable reducer or projection implementation version
     * @param outputFingerprint digest of the materialized output after applying the event
     * @return result copy
     */
    public AuthorityEventDispatchResult withProjection(String projectionVersion, String outputFingerprint) {
        return new AuthorityEventDispatchResult(
            status,
            message,
            projectionVersion,
            outputFingerprint,
            replayBatchId
        );
    }

    /**
     * Returns a copy of this result associated with a replay batch.
     *
     * @param replayBatchId replay batch id, or null for live dispatch
     * @return result copy
     */
    public AuthorityEventDispatchResult withReplayBatch(UUID replayBatchId) {
        return new AuthorityEventDispatchResult(
            status,
            message,
            projectionVersion,
            outputFingerprint,
            replayBatchId
        );
    }

    /**
     * Returns the dispatch status.
     *
     * @return dispatch status
     */
    public Status status() {
        return status;
    }

    /**
     * Returns the diagnostic message attached to retry or quarantine results.
     *
     * @return diagnostic message, or an empty string
     */
    public String message() {
        return message;
    }

    /**
     * Returns the projection implementation version that produced this result.
     *
     * @return projection version, or {@code unversioned}
     */
    public String projectionVersion() {
        return projectionVersion;
    }

    /**
     * Returns the consumer-provided output digest.
     *
     * @return output fingerprint, or an empty string when the dispatcher should derive one
     */
    public String outputFingerprint() {
        return outputFingerprint;
    }

    /**
     * Returns the replay batch that produced this result.
     *
     * @return replay batch id, or null for live dispatch
     */
    public UUID replayBatchId() {
        return replayBatchId;
    }

    /**
     * Indicates whether the consumer successfully handled the event.
     *
     * @return true when the status is {@link Status#SUCCESS}
     */
    public boolean successful() {
        return status == Status.SUCCESS;
    }
}
