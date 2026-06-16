package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.util.List;
import java.util.Objects;

public record ContentValidationReceipt(
        ArtifactPin artifactPin,
        ContentArtifactKind kind,
        ContentValidationStatus status,
        List<ContentValidationRejectionReason> rejectionReasons) {
    public ContentValidationReceipt {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        kind = Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        rejectionReasons = List.copyOf(Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
        if (status == ContentValidationStatus.ACCEPTED && !rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("accepted validation receipts must not carry rejection reasons");
        }
        if (status == ContentValidationStatus.REJECTED && rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("rejected validation receipts must carry rejection reasons");
        }
    }
}
