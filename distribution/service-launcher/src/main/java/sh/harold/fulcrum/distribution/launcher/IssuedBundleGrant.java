package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.api.HostSecurityContext;

record IssuedBundleGrant(
        String bundleId,
        HostSecurityContext securityContext,
        String grantFingerprint) {
}
