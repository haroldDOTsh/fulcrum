package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ContentArtifactCandidate(
        ArtifactPin artifactPin,
        ContentArtifactKind kind,
        String catalogRevision,
        Set<ExperienceId> experienceIds,
        Set<String> modeIds,
        Set<PoolId> poolIds,
        List<ContractPin> contractPins,
        String hostRuntimeAbi,
        Optional<String> stateCompatibilityVersion,
        ContentArtifactReadiness readiness,
        boolean enabled,
        int rotationWeight,
        int rotationOrder) {
    public ContentArtifactCandidate {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        ContentNames.requireDigestReference(artifactPin.digest(), "artifactPin.digest");
        kind = Objects.requireNonNull(kind, "kind");
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        experienceIds = Set.copyOf(Objects.requireNonNull(experienceIds, "experienceIds"));
        modeIds = ContentNames.copyStringSet(modeIds, "modeIds");
        poolIds = Set.copyOf(Objects.requireNonNull(poolIds, "poolIds"));
        contractPins = ContentNames.copyContractPins(contractPins, "contractPins");
        hostRuntimeAbi = ContentNames.requireNonBlank(hostRuntimeAbi, "hostRuntimeAbi");
        stateCompatibilityVersion = ContentNames.copyOptionalString(
                stateCompatibilityVersion,
                "stateCompatibilityVersion");
        if (kind == ContentArtifactKind.STATE_SNAPSHOT && stateCompatibilityVersion.isEmpty()) {
            throw new IllegalArgumentException("state snapshot candidates must declare a stateCompatibilityVersion");
        }
        readiness = Objects.requireNonNull(readiness, "readiness");
        if (rotationWeight <= 0) {
            throw new IllegalArgumentException("rotationWeight must be positive");
        }
    }
}
