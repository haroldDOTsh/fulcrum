package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.util.Objects;
import java.util.Optional;

public record SessionReceipt(
        SessionReceiptStatus status,
        Optional<String> rejectionReason,
        Optional<SessionId> sessionId,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<Long> ownerEpoch,
        Optional<SessionLifecycleStatus> lifecycleStatus,
        Optional<String> idempotencyKey,
        Optional<String> commandId) {
    public SessionReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason.map(value -> SessionNames.requireNonBlank(value, "reason"));
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        ownerEpoch = ownerEpoch == null ? Optional.empty() : ownerEpoch;
        lifecycleStatus = lifecycleStatus == null ? Optional.empty() : lifecycleStatus;
        idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey.map(value -> SessionNames.requireNonBlank(value, "idempotencyKey"));
        commandId = commandId == null ? Optional.empty() : commandId.map(value -> SessionNames.requireNonBlank(value, "commandId"));
    }

    static SessionReceipt accepted(
            SessionSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new SessionReceipt(
                SessionReceiptStatus.ACCEPTED,
                Optional.empty(),
                Optional.of(snapshot.sessionId()),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(snapshot.ownerEpoch()),
                Optional.of(snapshot.status()),
                Optional.of(idempotencyKey),
                Optional.of(commandId));
    }

    static SessionReceipt rejected(String reason) {
        return new SessionReceipt(
                SessionReceiptStatus.REJECTED,
                Optional.of(SessionNames.requireNonBlank(reason, "reason")),
                Optional.empty(),
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
                + "\nsessionId=" + sessionId.map(SessionId::value).orElse("")
                + "\nrevision=" + revision.map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + fencingEpoch.map(Object::toString).orElse("")
                + "\nownerEpoch=" + ownerEpoch.map(Object::toString).orElse("")
                + "\nlifecycleStatus=" + lifecycleStatus.map(SessionLifecycleStatus::name).orElse("")
                + "\nidempotencyKey=" + idempotencyKey.orElse("")
                + "\ncommandId=" + commandId.orElse("");
    }
}
