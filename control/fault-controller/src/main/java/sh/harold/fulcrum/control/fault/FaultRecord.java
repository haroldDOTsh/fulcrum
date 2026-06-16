package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record FaultRecord(
        FaultId faultId,
        FaultTargetType targetType,
        String targetId,
        String scope,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int count,
        QuarantineState quarantineState,
        String reason,
        TraceEnvelope traceEnvelope) {
    public FaultRecord {
        faultId = Objects.requireNonNull(faultId, "faultId");
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = ControlFaultStrings.requireNonBlank(targetId, "targetId");
        scope = ControlFaultStrings.requireNonBlank(scope, "scope");
        firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastSeenAt must not be before firstSeenAt");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        quarantineState = Objects.requireNonNull(quarantineState, "quarantineState");
        reason = ControlFaultStrings.requireNonBlank(reason, "reason");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static FaultRecord from(RecordFault command) {
        return new FaultRecord(
                command.faultId(),
                command.targetType(),
                command.targetId(),
                command.scope(),
                command.observedAt(),
                command.observedAt(),
                1,
                command.quarantineAfterCount() <= 1 ? QuarantineState.QUARANTINED : QuarantineState.OBSERVED,
                command.reason(),
                command.traceEnvelope());
    }

    FaultRecord recordAgain(RecordFault command) {
        int nextCount = count + 1;
        QuarantineState nextState = quarantineState == QuarantineState.QUARANTINED || nextCount >= command.quarantineAfterCount()
                ? QuarantineState.QUARANTINED
                : QuarantineState.OBSERVED;
        return new FaultRecord(
                faultId,
                targetType,
                targetId,
                scope,
                firstSeenAt,
                command.observedAt(),
                nextCount,
                nextState,
                reason,
                command.traceEnvelope());
    }

    FaultRecord release(ReleaseFault command) {
        return new FaultRecord(
                faultId,
                targetType,
                targetId,
                scope,
                firstSeenAt,
                command.releasedAt(),
                count,
                QuarantineState.RELEASED,
                command.releaseReason(),
                command.traceEnvelope());
    }

    public String wireValue(Revision revision) {
        return "faultId=" + faultId.value()
                + "|targetType=" + targetType.name()
                + "|targetId=" + targetId
                + "|scope=" + scope
                + "|count=" + count
                + "|quarantineState=" + quarantineState.name()
                + "|reason=" + reason
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
