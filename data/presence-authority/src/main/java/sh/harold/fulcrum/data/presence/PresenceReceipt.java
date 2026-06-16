package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record PresenceReceipt(
        PresenceReceiptStatus status,
        Optional<String> rejectionReason,
        Optional<PresenceId> presenceId,
        Optional<SubjectId> subjectId,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<String> idempotencyKey,
        Optional<String> commandId) {
    public PresenceReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason.map(value -> PresenceNames.requireNonBlank(value, "reason"));
        presenceId = presenceId == null ? Optional.empty() : presenceId;
        subjectId = subjectId == null ? Optional.empty() : subjectId;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey.map(value -> PresenceNames.requireNonBlank(value, "idempotencyKey"));
        commandId = commandId == null ? Optional.empty() : commandId.map(value -> PresenceNames.requireNonBlank(value, "commandId"));
    }

    static PresenceReceipt accepted(
            PresenceId presenceId,
            SubjectId subjectId,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new PresenceReceipt(
                PresenceReceiptStatus.ACCEPTED,
                Optional.empty(),
                Optional.of(presenceId),
                Optional.of(subjectId),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(idempotencyKey),
                Optional.of(commandId));
    }

    static PresenceReceipt rejected(String reason) {
        return new PresenceReceipt(
                PresenceReceiptStatus.REJECTED,
                Optional.of(PresenceNames.requireNonBlank(reason, "reason")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    String wireValue() {
        return "status=" + status.name()
                + "\nreason=" + rejectionReason.orElse("")
                + "\npresenceId=" + presenceId.map(PresenceId::value).orElse("")
                + "\nsubjectId=" + subjectId.map(value -> value.value().toString()).orElse("")
                + "\nrevision=" + revision.map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + fencingEpoch.map(Object::toString).orElse("")
                + "\nidempotencyKey=" + idempotencyKey.orElse("")
                + "\ncommandId=" + commandId.orElse("");
    }
}
