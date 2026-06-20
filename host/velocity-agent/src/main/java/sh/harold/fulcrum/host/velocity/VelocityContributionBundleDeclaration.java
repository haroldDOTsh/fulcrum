package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.util.Objects;

public record VelocityContributionBundleDeclaration(
        ArtifactPin artifactPin,
        String expectedDescriptorDigest,
        CapabilityMaterializationPlan materializationPlan) {
    public VelocityContributionBundleDeclaration {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        expectedDescriptorDigest = requireNonBlank(expectedDescriptorDigest, "expectedDescriptorDigest");
        materializationPlan = Objects.requireNonNull(materializationPlan, "materializationPlan");
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return trimmed;
    }
}
