package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.util.List;
import java.util.Objects;

public record ContentCandidateEvaluation(
        ArtifactPin artifactPin,
        ContentArtifactKind kind,
        String catalogRevision,
        boolean eligible,
        List<ContentRejectionReason> rejectionReasons) {
    public ContentCandidateEvaluation {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        kind = Objects.requireNonNull(kind, "kind");
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        rejectionReasons = List.copyOf(Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
        if (eligible != rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("eligible candidates must have no rejection reasons");
        }
    }
}
