package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CapabilityEnablementController {
    private final Map<IdempotencyKey, StoredCapabilityEnablementDecision> idempotencyLedger = new HashMap<>();

    public CapabilityEnablementDecision handle(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            CapabilityEnablementControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<CapabilityEnablementRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return rejected(command, currentRecord, trustBoundaryRejection.orElseThrow());
        }

        StoredCapabilityEnablementDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return rejected(command, currentRecord, CapabilityEnablementRejectionReason.IDEMPOTENCY_CONFLICT);
        }

        Optional<CapabilityEnablementRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            CapabilityEnablementDecision decision = rejected(command, currentRecord, commandRejection.orElseThrow());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredCapabilityEnablementDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        CapabilityEnablementDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredCapabilityEnablementDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static CapabilityEnablementControlRecord replay(
            CapabilityScope scope,
            long fencingEpoch,
            List<CapabilityEnablementEvent> events) {
        Objects.requireNonNull(events, "events");
        CapabilityEnablementControlRecord record = CapabilityEnablementControlRecord.empty(scope, fencingEpoch);
        for (CapabilityEnablementEvent event : events) {
            if (!event.scope().equals(scope)) {
                throw new IllegalArgumentException("event scope does not match replay scope");
            }
            record = record.withState(event.revision(), record.state().withBinding(event.binding()));
        }
        return record;
    }

    public static CapabilityEnablementControlRecord emptyRecord(CapabilityScope scope, long fencingEpoch) {
        return CapabilityEnablementControlRecord.empty(scope, fencingEpoch);
    }

    private static Optional<CapabilityEnablementRejectionReason> trustBoundaryRejection(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            CapabilityEnablementControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(CapabilityEnablementRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(CapabilityEnablementRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<CapabilityEnablementRejectionReason> commandRejection(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            CapabilityEnablementControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(CapabilityEnablementRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(CapabilityEnablementRejectionReason.REVISION_MISMATCH);
        }
        CapabilityScope scope = command.envelope().payload().scope();
        if (!command.envelope().aggregateId().equals(ControlCapabilityNames.aggregateId(scope))) {
            return Optional.of(CapabilityEnablementRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlCapabilityNames.CONTRACT)) {
            return Optional.of(CapabilityEnablementRejectionReason.CONTRACT_MISMATCH);
        }
        if (!currentRecord.state().scope().equals(scope)) {
            return Optional.of(CapabilityEnablementRejectionReason.SCOPE_MISMATCH);
        }
        return transitionRejection(command.envelope().payload(), currentRecord.state());
    }

    private static Optional<CapabilityEnablementRejectionReason> transitionRejection(
            CapabilityEnablementCommand command,
            CapabilityEnablementState state) {
        Optional<CapabilityBinding> current = state.binding(command.capabilityId());
        if (command instanceof EnableCapability) {
            return current.filter(CapabilityBinding::enabled).isPresent()
                    ? Optional.of(CapabilityEnablementRejectionReason.CAPABILITY_ALREADY_ENABLED)
                    : Optional.empty();
        }
        if (command instanceof DisableCapability) {
            return current.filter(CapabilityBinding::enabled).isPresent()
                    ? Optional.empty()
                    : Optional.of(CapabilityEnablementRejectionReason.CAPABILITY_NOT_ENABLED);
        }
        return Optional.of(CapabilityEnablementRejectionReason.UNKNOWN_COMMAND);
    }

    private static CapabilityEnablementDecision accepted(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            CapabilityEnablementControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        CapabilityBinding binding = nextBinding(command.envelope().payload(), currentRecord.state());
        CapabilityEnablementState nextState = currentRecord.state().withBinding(binding);
        CapabilityEnablementControlRecord nextRecord = currentRecord.withState(nextRevision, nextState);
        CapabilityEnablementEvent event = CapabilityEnablementEvent.from(command, nextRevision, binding);
        CapabilityEnablementReceipt receipt = CapabilityEnablementReceipt.accepted(
                nextState.scope(),
                binding,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        return CapabilityEnablementDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                emissions(nextState, event, receipt));
    }

    private static CapabilityBinding nextBinding(
            CapabilityEnablementCommand command,
            CapabilityEnablementState state) {
        if (command instanceof EnableCapability enable) {
            return CapabilityBinding.enabled(enable);
        }
        if (command instanceof DisableCapability disable) {
            return state.binding(disable.capabilityId()).orElseThrow().disabled(disable);
        }
        throw new IllegalArgumentException("unknown CapabilityEnablement command");
    }

    private static List<CapabilityEnablementEmission> emissions(
            CapabilityEnablementState state,
            CapabilityEnablementEvent event,
            CapabilityEnablementReceipt receipt) {
        CapabilityEnablementEmissionKind bindingKind = event.binding().enabled()
                ? CapabilityEnablementEmissionKind.CAPABILITY_ENABLED
                : CapabilityEnablementEmissionKind.CAPABILITY_DISABLED;
        return List.of(
                new CapabilityEnablementEmission(CapabilityEnablementEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                new CapabilityEnablementEmission(CapabilityEnablementEmissionKind.STATE, ControlCapabilityNames.stateKey(state.scope()), state.wireValue(event.revision())),
                new CapabilityEnablementEmission(CapabilityEnablementEmissionKind.RESPONSE, receipt.commandId(), receipt.wireValue()),
                new CapabilityEnablementEmission(bindingKind, event.scope().value() + ":" + event.capabilityId().value(), event.binding().wireValue()));
    }

    private static CapabilityEnablementDecision rejected(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command,
            CapabilityEnablementControlRecord currentRecord,
            CapabilityEnablementRejectionReason reason) {
        return CapabilityEnablementDecision.rejected(
                reason,
                currentRecord.revision(),
                currentRecord,
                command.envelope().traceEnvelope(),
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
    }
}

record StoredCapabilityEnablementDecision(String payloadFingerprint, CapabilityEnablementDecision decision) {
    StoredCapabilityEnablementDecision {
        payloadFingerprint = ControlCapabilityStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
