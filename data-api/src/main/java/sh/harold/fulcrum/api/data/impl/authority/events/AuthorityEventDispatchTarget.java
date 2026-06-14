package sh.harold.fulcrum.api.data.impl.authority.events;

/**
 * Registry-side authority event consumer.
 */
public interface AuthorityEventDispatchTarget {
    /**
     * Stable consumer name used for cursor and failure rows.
     *
     * @return unique consumer name
     */
    String consumerName();

    /**
     * Returns the projection compatibility manifest used to gate event delivery and checkpoint receipts.
     *
     * @return projection manifest
     */
    default AuthorityProjectionManifest projectionManifest() {
        return AuthorityProjectionManifest.unversioned(consumerName());
    }

    /**
     * Handles one authority event in log order for this consumer.
     *
     * @param event authority event envelope
     * @return dispatch result controlling cursor advancement
     * @throws Exception when the event should be treated as a retryable failure
     */
    AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) throws Exception;
}
