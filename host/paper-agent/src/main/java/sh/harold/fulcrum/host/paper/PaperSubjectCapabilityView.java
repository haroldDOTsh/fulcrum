package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record PaperSubjectCapabilityView(
        SubjectId subjectId,
        String displayName,
        Optional<String> rankLabel) {
    public PaperSubjectCapabilityView {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        displayName = PaperArtifactNames.requireNonBlank(displayName, "displayName");
        rankLabel = rankLabel == null ? Optional.empty() : rankLabel
                .map(value -> PaperArtifactNames.requireNonBlank(value, "rankLabel"));
    }

    public static PaperSubjectCapabilityView fallback(SubjectId subjectId, String username) {
        return new PaperSubjectCapabilityView(subjectId, username, Optional.empty());
    }

    public String decoratedDisplayName() {
        return rankLabel.map(label -> "[" + label + "] " + displayName).orElse(displayName);
    }
}
