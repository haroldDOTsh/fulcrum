package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;

interface BundleRuntimeAdapter {
    BundleRuntimeStartReceipt start(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant now);

    BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now);

    default BundleRuntimeObservation observe(BundleInstanceRecord record, Instant now) {
        java.util.Objects.requireNonNull(now, "now");
        return BundleRuntimeObservation.fromRecord(record);
    }
}
