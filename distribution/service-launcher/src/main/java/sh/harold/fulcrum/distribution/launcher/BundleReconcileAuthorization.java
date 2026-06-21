package sh.harold.fulcrum.distribution.launcher;

import java.util.Set;

record BundleReconcileAuthorization(
        Set<String> authorityDomains,
        Set<String> resourceClasses) {
    BundleReconcileAuthorization {
        authorityDomains = Set.copyOf(authorityDomains);
        resourceClasses = Set.copyOf(resourceClasses);
    }
}
