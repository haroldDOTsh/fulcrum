package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record ReleaseFault(
        FaultId faultId,
        String releaseReason,
        Instant releasedAt,
        TraceEnvelope traceEnvelope) implements FaultCommand {
    public ReleaseFault {
        faultId = Objects.requireNonNull(faultId, "faultId");
        releaseReason = ControlFaultStrings.requireNonBlank(releaseReason, "releaseReason");
        releasedAt = Objects.requireNonNull(releasedAt, "releasedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
