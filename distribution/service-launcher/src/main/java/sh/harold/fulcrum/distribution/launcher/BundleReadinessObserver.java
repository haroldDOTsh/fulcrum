package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;

interface BundleReadinessObserver {
    BundleReadinessReceipt observe(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            String launchNonce,
            Instant now);
}
