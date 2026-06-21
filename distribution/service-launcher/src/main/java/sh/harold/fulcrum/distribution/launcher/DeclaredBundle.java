package sh.harold.fulcrum.distribution.launcher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

record DeclaredBundle(
        String id,
        String artifactRef,
        String digest,
        String kind,
        String scope,
        String placementProfile,
        Optional<String> placementTier,
        List<String> authorityDomains,
        List<String> resourceClasses,
        boolean enabled) {
    DeclaredBundle {
        id = requireNonBlank(id, "id");
        artifactRef = requireNonBlank(artifactRef, "artifactRef");
        digest = requireNonBlank(digest, "digest");
        kind = requireNonBlank(kind, "kind");
        scope = requireNonBlank(scope, "scope");
        placementProfile = requireNonBlank(placementProfile, "placementProfile");
        placementTier = placementTier == null ? Optional.empty() : placementTier.map(value -> requireNonBlank(value, "placementTier"));
        authorityDomains = List.copyOf(Objects.requireNonNull(authorityDomains, "authorityDomains"));
        resourceClasses = List.copyOf(Objects.requireNonNull(resourceClasses, "resourceClasses"));
        if (!digest.startsWith("sha256:")) {
            throw new IllegalArgumentException("digest must be sha256-prefixed");
        }
        if (authorityDomains.isEmpty() && resourceClasses.isEmpty()) {
            throw new IllegalArgumentException("bundle must request at least one grant");
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
