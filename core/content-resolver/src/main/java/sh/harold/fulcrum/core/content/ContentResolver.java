package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContentResolver {
    private static final Comparator<ContentArtifactCandidate> CANDIDATE_ORDER = Comparator
            .comparing(ContentArtifactCandidate::kind)
            .thenComparingInt(ContentArtifactCandidate::rotationOrder)
            .thenComparing((left, right) -> Integer.compare(right.rotationWeight(), left.rotationWeight()))
            .thenComparing(candidate -> candidate.artifactPin().artifactId().value())
            .thenComparing(candidate -> candidate.artifactPin().digest())
            .thenComparing(ContentArtifactCandidate::catalogRevision);

    public ContentResolution resolve(
            ContentResolutionRequest request,
            ContentRotationPolicy policy,
            List<ContentArtifactCandidate> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        List<ContentArtifactCandidate> orderedCandidates = candidates == null
                ? List.of()
                : candidates.stream().filter(Objects::nonNull).sorted(CANDIDATE_ORDER).toList();

        List<ContentCandidateEvaluation> evaluations = new ArrayList<>();
        Map<ContentArtifactKind, List<ContentArtifactCandidate>> eligibleByKind = new EnumMap<>(ContentArtifactKind.class);
        for (ContentArtifactCandidate candidate : orderedCandidates) {
            ContentCandidateEvaluation evaluation = evaluate(request, policy, candidate);
            evaluations.add(evaluation);
            if (evaluation.eligible()) {
                eligibleByKind.computeIfAbsent(candidate.kind(), ignored -> new ArrayList<>()).add(candidate);
            }
        }

        List<ArtifactPin> selectedPins = new ArrayList<>();
        List<ContentArtifactKind> missingKinds = new ArrayList<>();
        for (ContentArtifactKind kind : policy.requiredKinds()) {
            List<ContentArtifactCandidate> eligible = eligibleByKind.getOrDefault(kind, List.of());
            if (eligible.isEmpty()) {
                missingKinds.add(kind);
            } else {
                selectedPins.add(eligible.get(0).artifactPin());
            }
        }

        ContentResolutionTrace trace = trace(request, policy, evaluations, missingKinds.isEmpty() ? selectedPins : List.of(), missingKinds);
        if (!missingKinds.isEmpty()) {
            return ContentResolution.rejected(trace);
        }

        ResolvedManifest manifest = new ResolvedManifest(
                request.resolvedManifestId(),
                request.codeArtifactId(),
                selectedPins,
                request.contractPins(),
                request.hostRuntimeAbi());
        return ContentResolution.resolved(manifest, trace);
    }

    private static ContentCandidateEvaluation evaluate(
            ContentResolutionRequest request,
            ContentRotationPolicy policy,
            ContentArtifactCandidate candidate) {
        List<ContentRejectionReason> reasons = new ArrayList<>();
        if (!policy.requiredKinds().contains(candidate.kind())) {
            reasons.add(ContentRejectionReason.KIND_NOT_REQUIRED);
        } else {
            if (candidate.readiness() != ContentArtifactReadiness.VALIDATED) {
                reasons.add(ContentRejectionReason.NOT_VALIDATED);
            }
            if (!candidate.enabled()) {
                reasons.add(ContentRejectionReason.DISABLED_BY_POLICY);
            }
            if (!candidate.experienceIds().isEmpty() && !candidate.experienceIds().contains(request.experienceId())) {
                reasons.add(ContentRejectionReason.EXPERIENCE_MISMATCH);
            }
            if (!candidate.modeIds().isEmpty()
                    && (request.modeId().isEmpty() || !candidate.modeIds().contains(request.modeId().orElseThrow()))) {
                reasons.add(ContentRejectionReason.MODE_MISMATCH);
            }
            if (!candidate.poolIds().isEmpty() && !candidate.poolIds().contains(request.poolId())) {
                reasons.add(ContentRejectionReason.POOL_MISMATCH);
            }
            if (!candidate.hostRuntimeAbi().equals(request.hostRuntimeAbi())) {
                reasons.add(ContentRejectionReason.HOST_RUNTIME_ABI_MISMATCH);
            }
            if (!candidate.contractPins().containsAll(request.contractPins())) {
                reasons.add(ContentRejectionReason.CONTRACT_MISMATCH);
            }
            if (candidate.stateCompatibilityVersion().isPresent()
                    && !candidate.stateCompatibilityVersion().equals(request.stateCompatibilityVersion())) {
                reasons.add(ContentRejectionReason.STATE_COMPATIBILITY_MISMATCH);
            }
        }
        return new ContentCandidateEvaluation(
                candidate.artifactPin(),
                candidate.kind(),
                candidate.catalogRevision(),
                reasons.isEmpty(),
                reasons);
    }

    private static ContentResolutionTrace trace(
            ContentResolutionRequest request,
            ContentRotationPolicy policy,
            List<ContentCandidateEvaluation> evaluations,
            List<ArtifactPin> selectedPins,
            List<ContentArtifactKind> missingKinds) {
        return new ContentResolutionTrace(
                request.resolverVersion(),
                policy.policyId(),
                policy.policyRevision(),
                policy.catalogRevision(),
                request.experienceId(),
                request.modeId(),
                request.poolId(),
                request.contractPins(),
                request.hostRuntimeAbi(),
                request.stateCompatibilityVersion(),
                evaluations,
                selectedPins,
                missingKinds);
    }
}
