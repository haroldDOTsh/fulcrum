package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record RegisterSubject(
        SubjectId subjectId,
        SubjectIdentityProvider identityProvider,
        SubjectExternalIdentity externalIdentity,
        Instant registeredAt) implements SubjectCommand {
    public RegisterSubject {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
    }
}
