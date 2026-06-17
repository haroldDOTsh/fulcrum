package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.UUID;

public record PaperJoiningSubject(
        UUID playerUuid,
        String playerName) {
    public PaperJoiningSubject {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        playerName = PaperArtifactNames.requireNonBlank(playerName, "playerName");
    }

    public SubjectId subjectId() {
        return new SubjectId(playerUuid);
    }
}
