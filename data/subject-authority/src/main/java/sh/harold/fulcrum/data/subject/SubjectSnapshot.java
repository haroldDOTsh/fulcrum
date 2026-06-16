package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SubjectSnapshot(
        SubjectId subjectId,
        SubjectIdentityProvider identityProvider,
        SubjectExternalIdentity externalIdentity,
        PrincipalId registeredBy,
        SubjectLifecycleStatus status,
        Instant registeredAt,
        Optional<PrincipalId> retiredBy,
        Optional<Instant> retiredAt,
        Optional<SubjectRetireReason> retireReason) {
    public SubjectSnapshot {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        registeredBy = Objects.requireNonNull(registeredBy, "registeredBy");
        status = Objects.requireNonNull(status, "status");
        registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
        retiredBy = retiredBy == null ? Optional.empty() : retiredBy;
        retiredAt = retiredAt == null ? Optional.empty() : retiredAt;
        retireReason = retireReason == null ? Optional.empty() : retireReason;
        if (status == SubjectLifecycleStatus.ACTIVE && (retiredBy.isPresent() || retiredAt.isPresent() || retireReason.isPresent())) {
            throw new IllegalArgumentException("active Subject cannot carry retirement fields");
        }
        if (status == SubjectLifecycleStatus.RETIRED && (retiredBy.isEmpty() || retiredAt.isEmpty() || retireReason.isEmpty())) {
            throw new IllegalArgumentException("retired Subject must carry retirement fields");
        }
        if (retiredAt.isPresent() && retiredAt.orElseThrow().isBefore(registeredAt)) {
            throw new IllegalArgumentException("retiredAt must not be before registeredAt");
        }
    }

    static SubjectSnapshot from(RegisterSubject command, PrincipalId registeredBy) {
        return new SubjectSnapshot(
                command.subjectId(),
                command.identityProvider(),
                command.externalIdentity(),
                registeredBy,
                SubjectLifecycleStatus.ACTIVE,
                command.registeredAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    SubjectSnapshot retire(RetireSubject command, PrincipalId retiredBy) {
        return new SubjectSnapshot(
                subjectId,
                identityProvider,
                externalIdentity,
                registeredBy,
                SubjectLifecycleStatus.RETIRED,
                registeredAt,
                Optional.of(retiredBy),
                Optional.of(command.retiredAt()),
                Optional.of(command.reason()));
    }

    String wireValue() {
        return "subjectId=" + subjectId.value()
                + "\nidentityProvider=" + identityProvider.name()
                + "\nexternalIdentity=" + externalIdentity.value()
                + "\nregisteredBy=" + registeredBy.value()
                + "\nstatus=" + status.name()
                + "\nregisteredAt=" + registeredAt
                + "\nretiredBy=" + retiredBy.map(PrincipalId::value).orElse("")
                + "\nretiredAt=" + retiredAt.map(Instant::toString).orElse("")
                + "\nretireReason=" + retireReason.map(SubjectRetireReason::name).orElse("");
    }
}
