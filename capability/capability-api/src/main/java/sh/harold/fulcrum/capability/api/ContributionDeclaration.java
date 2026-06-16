package sh.harold.fulcrum.capability.api;

import java.util.Objects;

public record ContributionDeclaration(String extensionPoint, CapabilityScope scope, int order) {
    public ContributionDeclaration {
        extensionPoint = CapabilityNames.requireNonBlank(extensionPoint, "extensionPoint");
        scope = Objects.requireNonNull(scope, "scope");
    }
}
