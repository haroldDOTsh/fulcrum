package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ContentResolutionRequest(
        ResolvedManifestId resolvedManifestId,
        ArtifactId codeArtifactId,
        ExperienceId experienceId,
        Optional<String> modeId,
        PoolId poolId,
        List<ContractPin> contractPins,
        String hostRuntimeAbi,
        Optional<String> stateCompatibilityVersion,
        String resolverVersion) {
    public ContentResolutionRequest {
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        codeArtifactId = Objects.requireNonNull(codeArtifactId, "codeArtifactId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = ContentNames.copyOptionalString(modeId, "modeId");
        poolId = Objects.requireNonNull(poolId, "poolId");
        contractPins = ContentNames.copyContractPins(contractPins, "contractPins");
        hostRuntimeAbi = ContentNames.requireNonBlank(hostRuntimeAbi, "hostRuntimeAbi");
        stateCompatibilityVersion = ContentNames.copyOptionalString(
                stateCompatibilityVersion,
                "stateCompatibilityVersion");
        resolverVersion = ContentNames.requireNonBlank(resolverVersion, "resolverVersion");
    }
}
