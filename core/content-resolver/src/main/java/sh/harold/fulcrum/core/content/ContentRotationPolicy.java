package sh.harold.fulcrum.core.content;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ContentRotationPolicy(
        String policyId,
        String policyRevision,
        String catalogRevision,
        List<ContentArtifactKind> requiredKinds) {
    public ContentRotationPolicy {
        policyId = ContentNames.requireNonBlank(policyId, "policyId");
        policyRevision = ContentNames.requireNonBlank(policyRevision, "policyRevision");
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        requiredKinds = List.copyOf(Objects.requireNonNull(requiredKinds, "requiredKinds"));
        if (requiredKinds.isEmpty()) {
            throw new IllegalArgumentException("requiredKinds must not be empty");
        }
        if (new HashSet<>(requiredKinds).size() != requiredKinds.size()) {
            throw new IllegalArgumentException("requiredKinds must not contain duplicates");
        }
    }
}
