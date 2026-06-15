package sh.harold.fulcrum.api.data.impl.authority.events;

/**
 * Projection that can rebuild itself from compacted authority state records.
 */
public interface AuthorityStateRestoreTarget {
    /**
     * Stable projection name used in restore evidence.
     *
     * @return projection name
     */
    String projectionName();

    /**
     * Version of the projection schema/reducer that accepts the state record.
     *
     * @return projection version
     */
    String projectionVersion();

    /**
     * Applies one compacted state record to this projection.
     *
     * @param record compacted state record
     * @return restore result
     */
    AuthorityStateRestoreResult restore(AuthorityStateRecord record);
}
