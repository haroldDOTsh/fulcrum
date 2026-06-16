package sh.harold.fulcrum.capability.api;

import java.util.Objects;

public record ContributionDeclaration(CapabilityExtensionPoint extensionPoint, CapabilityScope scope, int order) {
    public ContributionDeclaration {
        extensionPoint = Objects.requireNonNull(extensionPoint, "extensionPoint");
        scope = Objects.requireNonNull(scope, "scope");
        if (order < 0) {
            throw new IllegalArgumentException("order must be non-negative");
        }
    }
}
