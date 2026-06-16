package sh.harold.fulcrum.core.content;

import java.util.List;
import java.util.Objects;

public record ContentValidationReport(
        String catalogRevision,
        ContentValidationStatus status,
        List<ContentValidationReceipt> receipts,
        List<ContentArtifactCandidate> acceptedCandidates) {
    public ContentValidationReport {
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        status = Objects.requireNonNull(status, "status");
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
        acceptedCandidates = List.copyOf(Objects.requireNonNull(acceptedCandidates, "acceptedCandidates"));
        if (status == ContentValidationStatus.ACCEPTED && receipts.stream().anyMatch(receipt -> receipt.status() == ContentValidationStatus.REJECTED)) {
            throw new IllegalArgumentException("accepted validation reports must not contain rejected receipts");
        }
    }
}
