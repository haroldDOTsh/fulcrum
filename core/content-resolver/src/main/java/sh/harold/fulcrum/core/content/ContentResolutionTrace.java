package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ContentResolutionTrace(
        String resolverVersion,
        String policyId,
        String policyRevision,
        String catalogRevision,
        ExperienceId experienceId,
        Optional<String> modeId,
        PoolId poolId,
        List<ContractPin> contractPins,
        String hostRuntimeAbi,
        Optional<String> stateCompatibilityVersion,
        List<ContentCandidateEvaluation> candidateEvaluations,
        List<ArtifactPin> selectedPins,
        List<ContentArtifactKind> missingKinds) {
    public ContentResolutionTrace {
        resolverVersion = ContentNames.requireNonBlank(resolverVersion, "resolverVersion");
        policyId = ContentNames.requireNonBlank(policyId, "policyId");
        policyRevision = ContentNames.requireNonBlank(policyRevision, "policyRevision");
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = ContentNames.copyOptionalString(modeId, "modeId");
        poolId = Objects.requireNonNull(poolId, "poolId");
        contractPins = ContentNames.copyContractPins(contractPins, "contractPins");
        hostRuntimeAbi = ContentNames.requireNonBlank(hostRuntimeAbi, "hostRuntimeAbi");
        stateCompatibilityVersion = ContentNames.copyOptionalString(
                stateCompatibilityVersion,
                "stateCompatibilityVersion");
        candidateEvaluations = List.copyOf(Objects.requireNonNull(candidateEvaluations, "candidateEvaluations"));
        selectedPins = List.copyOf(Objects.requireNonNull(selectedPins, "selectedPins"));
        missingKinds = List.copyOf(Objects.requireNonNull(missingKinds, "missingKinds"));
        if (!missingKinds.isEmpty() && !selectedPins.isEmpty()) {
            throw new IllegalArgumentException("rejected traces must not carry partial selected pins");
        }
    }
}
