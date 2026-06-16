package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ContentCatalogEntry(
        ArtifactPin artifactPin,
        ContentArtifactKind kind,
        ArtifactObjectAddress objectAddress,
        Set<ExperienceId> experienceIds,
        Set<String> modeIds,
        Set<PoolId> poolIds,
        List<ContractPin> contractPins,
        String hostRuntimeAbi,
        Optional<String> stateCompatibilityVersion,
        boolean enabled,
        int rotationWeight,
        int rotationOrder,
        long byteLength) {
    public ContentCatalogEntry {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        kind = Objects.requireNonNull(kind, "kind");
        objectAddress = Objects.requireNonNull(objectAddress, "objectAddress");
        experienceIds = Set.copyOf(Objects.requireNonNull(experienceIds, "experienceIds"));
        modeIds = ContentNames.copyStringSet(modeIds, "modeIds");
        poolIds = Set.copyOf(Objects.requireNonNull(poolIds, "poolIds"));
        contractPins = ContentNames.copyContractPins(contractPins, "contractPins");
        hostRuntimeAbi = ContentNames.requireNonBlank(hostRuntimeAbi, "hostRuntimeAbi");
        stateCompatibilityVersion = ContentNames.copyOptionalString(
                stateCompatibilityVersion,
                "stateCompatibilityVersion");
    }
}
