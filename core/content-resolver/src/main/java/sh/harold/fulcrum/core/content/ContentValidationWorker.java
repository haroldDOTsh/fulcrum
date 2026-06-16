package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ContentValidationWorker {
    public ContentValidationReport validate(ContentCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        List<ContentValidationReceipt> receipts = new ArrayList<>();
        List<ContentArtifactCandidate> acceptedCandidates = new ArrayList<>();
        Set<String> artifactPins = new HashSet<>();
        Set<String> selectionOrders = new HashSet<>();

        for (ContentCatalogEntry entry : catalog.entries()) {
            List<ContentValidationRejectionReason> reasons = validateEntry(catalog, entry, artifactPins, selectionOrders);
            if (reasons.isEmpty()) {
                receipts.add(new ContentValidationReceipt(
                        entry.artifactPin(),
                        entry.kind(),
                        ContentValidationStatus.ACCEPTED,
                        List.of()));
                acceptedCandidates.add(candidate(catalog, entry));
            } else {
                receipts.add(new ContentValidationReceipt(
                        entry.artifactPin(),
                        entry.kind(),
                        ContentValidationStatus.REJECTED,
                        reasons));
            }
        }

        ContentValidationStatus status = receipts.stream().anyMatch(receipt -> receipt.status() == ContentValidationStatus.REJECTED)
                ? ContentValidationStatus.REJECTED
                : ContentValidationStatus.ACCEPTED;
        return new ContentValidationReport(catalog.catalogRevision(), status, receipts, acceptedCandidates);
    }

    private static List<ContentValidationRejectionReason> validateEntry(
            ContentCatalog catalog,
            ContentCatalogEntry entry,
            Set<String> artifactPins,
            Set<String> selectionOrders) {
        List<ContentValidationRejectionReason> reasons = new ArrayList<>();
        try {
            ArtifactObjectAddress expectedAddress = ArtifactBlobLayout.objectAddress(catalog.objectBucket(), entry.artifactPin());
            if (!expectedAddress.equals(entry.objectAddress())) {
                reasons.add(ContentValidationRejectionReason.OBJECT_ADDRESS_MISMATCH);
            }
        } catch (IllegalArgumentException exception) {
            reasons.add(ContentValidationRejectionReason.MALFORMED_DIGEST_REFERENCE);
        }
        if (entry.byteLength() <= 0) {
            reasons.add(ContentValidationRejectionReason.NON_POSITIVE_BYTE_LENGTH);
        }
        if (entry.rotationWeight() <= 0) {
            reasons.add(ContentValidationRejectionReason.NON_POSITIVE_ROTATION_WEIGHT);
        }
        if (entry.contractPins().isEmpty()) {
            reasons.add(ContentValidationRejectionReason.CONTRACT_PINS_MISSING);
        }
        if (entry.kind() == ContentArtifactKind.MAP_TEMPLATE
                && (entry.experienceIds().isEmpty() || entry.modeIds().isEmpty() || entry.poolIds().isEmpty())) {
            reasons.add(ContentValidationRejectionReason.MAP_TEMPLATE_SCOPE_MISSING);
        }
        if (entry.kind() == ContentArtifactKind.CONFIG_MODE
                && (entry.experienceIds().isEmpty() || entry.modeIds().isEmpty())) {
            reasons.add(ContentValidationRejectionReason.CONFIG_MODE_SCOPE_MISSING);
        }
        if (entry.kind() == ContentArtifactKind.STATE_SNAPSHOT && entry.stateCompatibilityVersion().isEmpty()) {
            reasons.add(ContentValidationRejectionReason.STATE_COMPATIBILITY_MISSING);
        }
        if (!artifactPins.add(artifactPinKey(entry))) {
            reasons.add(ContentValidationRejectionReason.DUPLICATE_ARTIFACT_PIN);
        }
        if (!selectionOrders.add(selectionOrderKey(entry))) {
            reasons.add(ContentValidationRejectionReason.DUPLICATE_SELECTION_ORDER);
        }
        return reasons;
    }

    private static ContentArtifactCandidate candidate(ContentCatalog catalog, ContentCatalogEntry entry) {
        return new ContentArtifactCandidate(
                entry.artifactPin(),
                entry.kind(),
                catalog.catalogRevision(),
                entry.experienceIds(),
                entry.modeIds(),
                entry.poolIds(),
                entry.contractPins(),
                entry.hostRuntimeAbi(),
                entry.stateCompatibilityVersion(),
                ContentArtifactReadiness.VALIDATED,
                entry.enabled(),
                entry.rotationWeight(),
                entry.rotationOrder());
    }

    private static String artifactPinKey(ContentCatalogEntry entry) {
        String digestKey;
        try {
            digestKey = ArtifactBlobLayout.digestFor(entry.artifactPin()).wireValue();
        } catch (IllegalArgumentException exception) {
            digestKey = entry.artifactPin().digest();
        }
        return entry.kind()
                + "|"
                + entry.artifactPin().artifactId().value()
                + "|"
                + digestKey
                + "|"
                + entry.artifactPin().compatibility();
    }

    private static String selectionOrderKey(ContentCatalogEntry entry) {
        return entry.kind()
                + "|"
                + entry.experienceIds().stream().map(ExperienceId::value).sorted().toList()
                + "|"
                + entry.modeIds().stream().sorted().toList()
                + "|"
                + entry.poolIds().stream().map(PoolId::value).sorted().toList()
                + "|"
                + entry.rotationOrder();
    }
}
