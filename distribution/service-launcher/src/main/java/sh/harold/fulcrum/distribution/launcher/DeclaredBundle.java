package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.capability.api.ContributionDeclaration;

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
        Optional<String> backendImageRef,
        Optional<String> backendImageDigest,
        List<String> authorityDomains,
        List<String> resourceClasses,
        Optional<String> descriptorDigest,
        List<ContributionDeclaration> contributions,
        boolean enabled) {
    DeclaredBundle {
        id = requireNonBlank(id, "id");
        artifactRef = requireNonBlank(artifactRef, "artifactRef");
        digest = requireNonBlank(digest, "digest");
        kind = requireNonBlank(kind, "kind");
        scope = requireNonBlank(scope, "scope");
        placementProfile = requireNonBlank(placementProfile, "placementProfile");
        placementTier = placementTier == null ? Optional.empty() : placementTier.map(value -> requireNonBlank(value, "placementTier"));
        backendImageRef = backendImageRef == null ? Optional.empty() : backendImageRef
                .map(value -> requireNonBlank(value, "backendImageRef"));
        backendImageDigest = backendImageDigest == null ? Optional.empty() : backendImageDigest
                .map(value -> requireNonBlank(value, "backendImageDigest"));
        authorityDomains = List.copyOf(Objects.requireNonNull(authorityDomains, "authorityDomains"));
        resourceClasses = List.copyOf(Objects.requireNonNull(resourceClasses, "resourceClasses"));
        descriptorDigest = descriptorDigest == null ? Optional.empty() : descriptorDigest
                .map(value -> requireNonBlank(value, "descriptorDigest"));
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        if (!digest.startsWith("sha256:")) {
            throw new IllegalArgumentException("digest must be sha256-prefixed");
        }
        if (backendImageRef.isPresent() != backendImageDigest.isPresent()) {
            throw new IllegalArgumentException("backend image reference and digest must be supplied together");
        }
        if (backendImageDigest.isPresent()) {
            String checkedImageDigest = backendImageDigest.orElseThrow();
            if (!checkedImageDigest.startsWith("sha256:")) {
                throw new IllegalArgumentException("backend image digest must be sha256-prefixed");
            }
            if (!backendImageRef.orElseThrow().contains("@" + checkedImageDigest)) {
                throw new IllegalArgumentException("backend image reference must be pinned with backend image digest");
            }
        }
        if (kind.equals("authority-backend") && backendImageRef.isEmpty()) {
            throw new IllegalArgumentException("authority-backend bundles must declare a digest-pinned backend image");
        }
        if (kind.equals("contribution") && backendImageRef.isPresent()) {
            throw new IllegalArgumentException("contribution bundles must not declare a backend image");
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
