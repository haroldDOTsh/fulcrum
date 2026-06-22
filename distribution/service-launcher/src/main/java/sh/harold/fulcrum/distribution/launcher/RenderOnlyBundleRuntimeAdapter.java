package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;

final class RenderOnlyBundleRuntimeAdapter implements BundleRuntimeAdapter {
    @Override
    public BundleRuntimeStartReceipt start(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant now) {
        java.util.Objects.requireNonNull(rendered, "rendered");
        java.util.Objects.requireNonNull(manifest, "manifest");
        java.util.Objects.requireNonNull(now, "now");
        return BundleRuntimeStartReceipt.renderOnly();
    }

    @Override
    public BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now) {
        java.util.Objects.requireNonNull(record, "record");
        java.util.Objects.requireNonNull(now, "now");
        return BundleRuntimeStopReceipt.skipped("runtime-never-started");
    }
}
