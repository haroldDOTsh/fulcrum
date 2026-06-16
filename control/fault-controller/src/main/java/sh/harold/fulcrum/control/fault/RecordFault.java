package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record RecordFault(
        FaultId faultId,
        FaultTargetType targetType,
        String targetId,
        String scope,
        String reason,
        int quarantineAfterCount,
        Instant observedAt,
        TraceEnvelope traceEnvelope) implements FaultCommand {
    public RecordFault {
        faultId = Objects.requireNonNull(faultId, "faultId");
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = ControlFaultStrings.requireNonBlank(targetId, "targetId");
        scope = ControlFaultStrings.requireNonBlank(scope, "scope");
        reason = ControlFaultStrings.requireNonBlank(reason, "reason");
        if (quarantineAfterCount <= 0) {
            throw new IllegalArgumentException("quarantineAfterCount must be positive");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
