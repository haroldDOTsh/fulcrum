package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.util.Objects;

public record PaperContributionBundleDeclaration(
        ArtifactPin artifactPin,
        String expectedDescriptorDigest,
        CapabilityMaterializationPlan materializationPlan) {
    public PaperContributionBundleDeclaration {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        expectedDescriptorDigest = PaperArtifactNames.requireNonBlank(
                expectedDescriptorDigest,
                "expectedDescriptorDigest");
        materializationPlan = Objects.requireNonNull(materializationPlan, "materializationPlan");
    }
}
