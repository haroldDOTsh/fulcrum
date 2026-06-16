package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.Objects;

public record MarkInstanceDraining(
        InstanceId instanceId,
        String reason,
        Instant drainingAt,
        TraceEnvelope traceEnvelope) implements InstanceRegistryCommand {
    public MarkInstanceDraining {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        reason = ControlInstanceStrings.requireNonBlank(reason, "reason");
        drainingAt = Objects.requireNonNull(drainingAt, "drainingAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
