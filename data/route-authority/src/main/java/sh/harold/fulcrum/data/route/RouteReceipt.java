package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record RouteReceipt(
        RouteReceiptStatus status,
        Optional<String> rejectionReason,
        Optional<RouteId> routeId,
        Optional<SubjectId> subjectId,
        Optional<Revision> revision,
        Optional<Long> fencingEpoch,
        Optional<RouteLifecycleStatus> lifecycleStatus,
        Optional<String> idempotencyKey,
        Optional<String> commandId) {
    public RouteReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason.map(value -> RouteNames.requireNonBlank(value, "reason"));
        routeId = routeId == null ? Optional.empty() : routeId;
        subjectId = subjectId == null ? Optional.empty() : subjectId;
        revision = revision == null ? Optional.empty() : revision;
        fencingEpoch = fencingEpoch == null ? Optional.empty() : fencingEpoch;
        lifecycleStatus = lifecycleStatus == null ? Optional.empty() : lifecycleStatus;
        idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey.map(value -> RouteNames.requireNonBlank(value, "idempotencyKey"));
        commandId = commandId == null ? Optional.empty() : commandId.map(value -> RouteNames.requireNonBlank(value, "commandId"));
    }

    static RouteReceipt accepted(
            RouteSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new RouteReceipt(
                RouteReceiptStatus.ACCEPTED,
                Optional.empty(),
                Optional.of(snapshot.routeId()),
                Optional.of(snapshot.subjectId()),
                Optional.of(revision),
                Optional.of(fencingEpoch),
                Optional.of(snapshot.status()),
                Optional.of(idempotencyKey),
                Optional.of(commandId));
    }

    static RouteReceipt rejected(String reason) {
        return new RouteReceipt(
                RouteReceiptStatus.REJECTED,
                Optional.of(RouteNames.requireNonBlank(reason, "reason")),
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
                + "\nrouteId=" + routeId.map(RouteId::value).orElse("")
                + "\nsubjectId=" + subjectId.map(value -> value.value().toString()).orElse("")
                + "\nrevision=" + revision.map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + fencingEpoch.map(Object::toString).orElse("")
                + "\nlifecycleStatus=" + lifecycleStatus.map(RouteLifecycleStatus::name).orElse("")
                + "\nidempotencyKey=" + idempotencyKey.orElse("")
                + "\ncommandId=" + commandId.orElse("");
    }
}
