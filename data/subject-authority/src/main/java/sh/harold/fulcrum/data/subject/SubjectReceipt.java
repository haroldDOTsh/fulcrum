package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record SubjectReceipt(
        SubjectReceiptStatus status,
        Optional<String> rejectionReason,
        Optional<SubjectId> subjectId,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<SubjectLifecycleStatus> lifecycleStatus,
        Optional<String> idempotencyKey,
        Optional<String> commandId) {
    public SubjectReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason.map(value -> SubjectNames.requireNonBlank(value, "reason"));
        subjectId = subjectId == null ? Optional.empty() : subjectId;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        lifecycleStatus = lifecycleStatus == null ? Optional.empty() : lifecycleStatus;
        idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey.map(value -> SubjectNames.requireNonBlank(value, "idempotencyKey"));
        commandId = commandId == null ? Optional.empty() : commandId.map(value -> SubjectNames.requireNonBlank(value, "commandId"));
    }

    static SubjectReceipt accepted(
            SubjectSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new SubjectReceipt(
                SubjectReceiptStatus.ACCEPTED,
                Optional.empty(),
                Optional.of(snapshot.subjectId()),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(snapshot.status()),
                Optional.of(idempotencyKey),
                Optional.of(commandId));
    }

    static SubjectReceipt rejected(String reason) {
        return new SubjectReceipt(
                SubjectReceiptStatus.REJECTED,
                Optional.of(SubjectNames.requireNonBlank(reason, "reason")),
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
                + "\nsubjectId=" + subjectId.map(value -> value.value().toString()).orElse("")
                + "\nrevision=" + revision.map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + fencingEpoch.map(Object::toString).orElse("")
                + "\nlifecycleStatus=" + lifecycleStatus.map(SubjectLifecycleStatus::name).orElse("")
                + "\nidempotencyKey=" + idempotencyKey.orElse("")
                + "\ncommandId=" + commandId.orElse("");
    }
}
