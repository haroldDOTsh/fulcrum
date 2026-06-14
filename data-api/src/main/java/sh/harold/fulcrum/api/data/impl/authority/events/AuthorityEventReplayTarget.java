package sh.harold.fulcrum.api.data.impl.authority.events;

/**
 * Dry-run projection reducer used to verify existing projection checkpoints without mutating live read state.
 */
public interface AuthorityEventReplayTarget {
    /**
     * Returns the projection name whose checkpoint receipts should be verified.
     *
     * @return projection name
     */
    String projectionName();

    /**
     * Returns the projection compatibility manifest used to verify replay compatibility.
     *
     * @return projection manifest
     */
    default AuthorityProjectionManifest projectionManifest() {
        return AuthorityProjectionManifest.unversioned(projectionName());
    }

    /**
     * Replays one authority event without writing to production projection tables, cursors, heads, or checkpoints.
     *
     * @param event authority event to replay
     * @return deterministic replay result
     * @throws Exception when replay cannot evaluate the event
     */
    AuthorityEventReplayResult replay(AuthorityEventEnvelope event) throws Exception;
}
