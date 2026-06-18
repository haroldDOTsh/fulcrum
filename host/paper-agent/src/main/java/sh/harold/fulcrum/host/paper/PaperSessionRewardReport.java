package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PaperSessionRewardReport(
        InstanceId instanceId,
        SessionId sessionId,
        RouteId routeId,
        SubjectId subjectId,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public PaperSessionRewardReport {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static PaperSessionRewardReport fromAttachmentObservation(HostObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!HostObservationTypes.SESSION_ATTACHED.equals(observation.observationType())) {
            throw new IllegalArgumentException(
                    "Paper reward report requires session-attached observation, got "
                            + observation.observationType());
        }
        Map<String, String> attributes = observation.attributes();
        return new PaperSessionRewardReport(
                observation.instanceId(),
                new SessionId(required(attributes, "sessionId")),
                new RouteId(required(attributes, "routeId")),
                new SubjectId(UUID.fromString(required(attributes, "subjectId"))),
                observation.traceEnvelope(),
                observation.observedAt());
    }

    private static String required(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Paper reward report attribute " + key);
        }
        return value;
    }
}
