package sh.harold.fulcrum.host.api;

import java.util.Map;
import java.util.Objects;

public final class HostObservationFactory {
    private HostObservationFactory() {
    }

    public static HostObservation readiness(HostReadinessReport report) {
        Objects.requireNonNull(report, "report");
        HostInstanceIdentity identity = report.instanceIdentity();
        return new HostObservation(
                identity.instanceId(),
                HostObservationTypes.READINESS,
                report.traceEnvelope(),
                report.readyAt(),
                Map.of(
                        "instanceKind", identity.instanceKind(),
                        "poolId", identity.poolId().value(),
                        "machineRef", identity.machineRef().value(),
                        "principalId", identity.principalId().value(),
                        "resolvedManifestId", report.resolvedManifestId().value()));
    }

    public static HostObservation sessionAttached(HostSessionAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        HostInstanceIdentity identity = attachment.instanceIdentity();
        return new HostObservation(
                identity.instanceId(),
                HostObservationTypes.SESSION_ATTACHED,
                attachment.traceEnvelope(),
                attachment.attachedAt(),
                Map.of(
                        "instanceKind", identity.instanceKind(),
                        "poolId", identity.poolId().value(),
                        "routeId", attachment.routeId().value(),
                        "subjectId", attachment.subjectId().value().toString(),
                        "sessionId", attachment.sessionId().value()));
    }

    public static HostObservation sessionDetached(HostSessionDetachment detachment) {
        Objects.requireNonNull(detachment, "detachment");
        HostInstanceIdentity identity = detachment.instanceIdentity();
        return new HostObservation(
                identity.instanceId(),
                HostObservationTypes.SESSION_DETACHED,
                detachment.traceEnvelope(),
                detachment.detachedAt(),
                Map.of(
                        "instanceKind", identity.instanceKind(),
                        "poolId", identity.poolId().value(),
                        "routeId", detachment.routeId().value(),
                        "subjectId", detachment.subjectId().value().toString(),
                        "sessionId", detachment.sessionId().value()));
    }
}
