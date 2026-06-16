package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.Objects;

public record MarkInstanceOffline(
        InstanceId instanceId,
        String reason,
        Instant offlineAt,
        TraceEnvelope traceEnvelope) implements InstanceRegistryCommand {
    public MarkInstanceOffline {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        reason = ControlInstanceStrings.requireNonBlank(reason, "reason");
        offlineAt = Objects.requireNonNull(offlineAt, "offlineAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
