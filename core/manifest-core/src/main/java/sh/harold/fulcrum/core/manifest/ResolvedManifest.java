package sh.harold.fulcrum.core.manifest;

import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.util.List;
import java.util.Objects;

public record ResolvedManifest(
        ResolvedManifestId resolvedManifestId,
        ArtifactId codeArtifactId,
        List<ArtifactPin> contentArtifacts,
        List<ContractPin> contractPins,
        String hostRuntimeAbi) {
    public ResolvedManifest {
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        codeArtifactId = Objects.requireNonNull(codeArtifactId, "codeArtifactId");
        contentArtifacts = List.copyOf(Objects.requireNonNull(contentArtifacts, "contentArtifacts"));
        contractPins = List.copyOf(Objects.requireNonNull(contractPins, "contractPins"));
        hostRuntimeAbi = ManifestNames.requireNonBlank(hostRuntimeAbi, "hostRuntimeAbi");
    }
}
