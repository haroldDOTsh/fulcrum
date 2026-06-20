package sh.harold.fulcrum.sdk.authoring;

import java.util.List;
import java.util.Objects;

public record AuthorBundlePreflightReceipt(
        AuthorBundlePreflightStatus status,
        String bundleId,
        String substrateFingerprint,
        String sdkCoordinate,
        String descriptorDigest,
        String artifactDigest,
        String planDigest,
        List<AuthorBundlePreflightRefusal> refusals) {
    public AuthorBundlePreflightReceipt {
        status = Objects.requireNonNull(status, "status");
        bundleId = AuthoringNames.requireNonBlank(bundleId, "bundleId");
        substrateFingerprint = AuthoringNames.requireNonBlank(substrateFingerprint, "substrateFingerprint");
        sdkCoordinate = AuthoringNames.requireNonBlank(sdkCoordinate, "sdkCoordinate");
        descriptorDigest = AuthoringNames.requireNonBlank(descriptorDigest, "descriptorDigest");
        artifactDigest = AuthoringNames.requireNonBlank(artifactDigest, "artifactDigest");
        planDigest = AuthoringNames.requireNonBlank(planDigest, "planDigest");
        refusals = List.copyOf(Objects.requireNonNull(refusals, "refusals"));
        if (status == AuthorBundlePreflightStatus.ACCEPTED && !refusals.isEmpty()) {
            throw new IllegalArgumentException("accepted preflight receipts cannot carry refusals");
        }
        if (status == AuthorBundlePreflightStatus.REFUSED && refusals.isEmpty()) {
            throw new IllegalArgumentException("refused preflight receipts must carry refusal codes");
        }
    }
}
