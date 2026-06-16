package sh.harold.fulcrum.capability.runtime;

import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.List;
import java.util.Objects;

public record CapabilityContributionPipeline(
        CapabilityExtensionPoint extensionPoint,
        CapabilityScope scope,
        List<CapabilityMaterializationPlan.ContributionRegistration> registrations) {
    public CapabilityContributionPipeline {
        extensionPoint = Objects.requireNonNull(extensionPoint, "extensionPoint");
        scope = Objects.requireNonNull(scope, "scope");
        registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations"));
    }
}
