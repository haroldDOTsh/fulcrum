package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record RouteAttemptEvent(
        String eventType,
        RouteAttemptSnapshot snapshot,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public RouteAttemptEvent {
        eventType = ControlRouteStrings.requireNonBlank(eventType, "eventType");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static RouteAttemptEvent from(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            Revision revision,
            RouteAttemptSnapshot snapshot) {
        return new RouteAttemptEvent(
                "ctrl.route-attempt." + snapshot.status().name().toLowerCase(),
                snapshot,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.route-attempt:" + snapshot.routeAttemptId().value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|routeAttemptId=" + snapshot.routeAttemptId().value()
                + "|status=" + snapshot.status().name()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
