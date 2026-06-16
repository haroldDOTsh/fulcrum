package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.Objects;

public record CapabilityEnablementControlRecord(
        Revision revision,
        long fencingEpoch,
        CapabilityEnablementState state) {
    public CapabilityEnablementControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        state = Objects.requireNonNull(state, "state");
    }

    public static CapabilityEnablementControlRecord empty(CapabilityScope scope, long fencingEpoch) {
        return new CapabilityEnablementControlRecord(new Revision(0), fencingEpoch, CapabilityEnablementState.empty(scope));
    }

    public CapabilityEnablementControlRecord withState(Revision revision, CapabilityEnablementState state) {
        return new CapabilityEnablementControlRecord(revision, fencingEpoch, state);
    }
}
