package sh.harold.fulcrum.api.data.impl.authority.events;

import java.util.List;
import java.util.Objects;

/**
 * Applies compacted state records to a primary projection and additional sidecar projections.
 */
public final class FanoutAuthorityStateRestoreTarget implements AuthorityStateRestoreTarget {
    private final AuthorityStateRestoreTarget primary;
    private final List<AuthorityStateRestoreTarget> sidecars;

    public FanoutAuthorityStateRestoreTarget(
        AuthorityStateRestoreTarget primary,
        List<AuthorityStateRestoreTarget> sidecars
    ) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.sidecars = sidecars == null ? List.of() : List.copyOf(sidecars);
    }

    @Override
    public String projectionName() {
        return primary.projectionName();
    }

    @Override
    public String projectionVersion() {
        return primary.projectionVersion();
    }

    @Override
    public AuthorityStateRestoreResult restore(AuthorityStateRecord record) {
        AuthorityStateRestoreResult primaryResult = primary.restore(record);
        boolean restored = primaryResult != null && primaryResult.restored();
        for (AuthorityStateRestoreTarget sidecar : sidecars) {
            AuthorityStateRestoreResult sidecarResult = sidecar.restore(record);
            restored = restored || sidecarResult != null && sidecarResult.restored();
        }
        if (restored) {
            return AuthorityStateRestoreResult.restored(primary.projectionVersion(), record);
        }
        return primaryResult == null
            ? AuthorityStateRestoreResult.skipped(primary.projectionVersion(), record, "existing projection is newer or equal")
            : primaryResult;
    }
}
