package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record InstanceRegistryRecord(
        Revision revision,
        long fencingEpoch,
        Optional<InstanceSnapshot> snapshot) {
    public InstanceRegistryRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        snapshot = snapshot == null ? Optional.empty() : snapshot;
    }

    public static InstanceRegistryRecord empty(long fencingEpoch) {
        return new InstanceRegistryRecord(new Revision(0), fencingEpoch, Optional.empty());
    }

    public InstanceRegistryRecord withSnapshot(Revision revision, InstanceSnapshot snapshot) {
        return new InstanceRegistryRecord(revision, fencingEpoch, Optional.of(snapshot));
    }
}
