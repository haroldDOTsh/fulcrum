package sh.harold.fulcrum.api.data.impl.authority.events;

/**
 * Result of applying a compacted state record to a hot projection.
 */
public record AuthorityStateRestoreResult(
    boolean restored,
    String projectionVersion,
    String aggregateScope,
    long restoredRevision,
    String stateFingerprint,
    String message
) {
    public static AuthorityStateRestoreResult restored(
        String projectionVersion,
        AuthorityStateRecord record
    ) {
        return new AuthorityStateRestoreResult(
            true,
            projectionVersion,
            record.aggregateScope(),
            record.revision(),
            record.stateFingerprint(),
            "restored"
        );
    }

    public static AuthorityStateRestoreResult skipped(
        String projectionVersion,
        AuthorityStateRecord record,
        String message
    ) {
        return new AuthorityStateRestoreResult(
            false,
            projectionVersion,
            record.aggregateScope(),
            record.revision(),
            record.stateFingerprint(),
            message
        );
    }
}
