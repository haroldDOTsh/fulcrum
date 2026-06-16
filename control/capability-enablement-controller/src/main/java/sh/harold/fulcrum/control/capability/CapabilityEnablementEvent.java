package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.time.Instant;
import java.util.Objects;

public record CapabilityEnablementEvent(
        String eventType,
        CapabilityScope scope,
        CapabilityId capabilityId,
        CapabilityBinding binding,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public CapabilityEnablementEvent {
        eventType = ControlCapabilityStrings.requireNonBlank(eventType, "eventType");
        scope = Objects.requireNonNull(scope, "scope");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        binding = Objects.requireNonNull(binding, "binding");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static CapabilityEnablementEvent from(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            Revision revision,
            CapabilityBinding binding) {
        return new CapabilityEnablementEvent(
                "ctrl.capability-enablement." + (binding.enabled() ? "enabled" : "disabled"),
                command.envelope().payload().scope(),
                command.envelope().payload().capabilityId(),
                binding,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.capability-enablement:" + scope.value() + ":" + capabilityId.value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|scope=" + scope.value()
                + "|capabilityId=" + capabilityId.value()
                + "|enabled=" + binding.enabled()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
