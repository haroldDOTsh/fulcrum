package sh.harold.fulcrum.capability.runtime;

import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.List;
import java.util.Objects;

public record CapabilityContributionComposition(
        CapabilityScope scope,
        List<CapabilityContributionPipeline> pipelines) {
    public CapabilityContributionComposition {
        scope = Objects.requireNonNull(scope, "scope");
        pipelines = List.copyOf(Objects.requireNonNull(pipelines, "pipelines"));
    }

    public List<CapabilityMaterializationPlan.ContributionRegistration> registrationsFor(
            CapabilityExtensionPoint extensionPoint) {
        CapabilityExtensionPoint requestedExtensionPoint = Objects.requireNonNull(extensionPoint, "extensionPoint");
        return pipelines.stream()
                .filter(pipeline -> pipeline.extensionPoint() == requestedExtensionPoint)
                .findFirst()
                .map(CapabilityContributionPipeline::registrations)
                .orElse(List.of());
    }
}
