package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InstanceRegistryController {
    private final Map<IdempotencyKey, StoredInstanceRegistryDecision> idempotencyLedger = new HashMap<>();

    public InstanceRegistryDecision handle(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            InstanceRegistryRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<InstanceRegistryRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return rejected(command, currentRecord, trustBoundaryRejection.orElseThrow());
        }

        StoredInstanceRegistryDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return rejected(command, currentRecord, InstanceRegistryRejectionReason.IDEMPOTENCY_CONFLICT);
        }

        Optional<InstanceRegistryRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            InstanceRegistryDecision decision = rejected(command, currentRecord, commandRejection.orElseThrow());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredInstanceRegistryDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        InstanceRegistryDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredInstanceRegistryDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static InstanceRegistryRecord replay(long fencingEpoch, List<InstanceRegistryEvent> events) {
        Objects.requireNonNull(events, "events");
        InstanceRegistryRecord record = InstanceRegistryRecord.empty(fencingEpoch);
        for (InstanceRegistryEvent event : events) {
            record = record.withSnapshot(event.revision(), event.snapshot());
        }
        return record;
    }

    public static InstanceRegistryRecord emptyRecord(long fencingEpoch) {
        return InstanceRegistryRecord.empty(fencingEpoch);
    }

    private static Optional<InstanceRegistryRejectionReason> trustBoundaryRejection(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            InstanceRegistryRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(InstanceRegistryRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(InstanceRegistryRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<InstanceRegistryRejectionReason> commandRejection(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            InstanceRegistryRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(InstanceRegistryRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(InstanceRegistryRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlInstanceNames.aggregateId(command.envelope().payload().instanceId()))) {
            return Optional.of(InstanceRegistryRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlInstanceNames.CONTRACT)) {
            return Optional.of(InstanceRegistryRejectionReason.CONTRACT_MISMATCH);
        }
        return transitionRejection(command.envelope().payload(), currentRecord);
    }

    private static Optional<InstanceRegistryRejectionReason> transitionRejection(
            InstanceRegistryCommand command,
            InstanceRegistryRecord currentRecord) {
        Optional<InstanceSnapshot> current = currentRecord.snapshot();
        if (command instanceof RegisterInstance) {
            return current.isPresent()
                    ? Optional.of(InstanceRegistryRejectionReason.INSTANCE_ALREADY_REGISTERED)
                    : Optional.empty();
        }
        if (current.isEmpty()) {
            return Optional.of(InstanceRegistryRejectionReason.INSTANCE_NOT_REGISTERED);
        }
        if (current.orElseThrow().status() == InstanceRegistryStatus.OFFLINE) {
            return Optional.of(InstanceRegistryRejectionReason.INSTANCE_OFFLINE);
        }
        if (command instanceof MarkInstanceReady || command instanceof MarkInstanceDraining || command instanceof MarkInstanceOffline) {
            return Optional.empty();
        }
        return Optional.of(InstanceRegistryRejectionReason.UNKNOWN_COMMAND);
    }

    private static InstanceRegistryDecision accepted(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            InstanceRegistryRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        InstanceSnapshot snapshot = nextSnapshot(command.envelope().payload(), currentRecord);
        InstanceRegistryRecord nextRecord = currentRecord.withSnapshot(nextRevision, snapshot);
        InstanceRegistryEvent event = InstanceRegistryEvent.from(command, nextRevision, snapshot);
        InstanceRegistryReceipt receipt = InstanceRegistryReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        return InstanceRegistryDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                emissions(snapshot, event, receipt));
    }

    private static InstanceSnapshot nextSnapshot(
            InstanceRegistryCommand command,
            InstanceRegistryRecord currentRecord) {
        if (command instanceof RegisterInstance register) {
            return InstanceSnapshot.from(register);
        }
        InstanceSnapshot current = currentRecord.snapshot().orElseThrow();
        if (command instanceof MarkInstanceReady ready) {
            return current.ready(ready);
        }
        if (command instanceof MarkInstanceDraining draining) {
            return current.draining(draining);
        }
        if (command instanceof MarkInstanceOffline offline) {
            return current.offline(offline);
        }
        throw new IllegalArgumentException("unknown InstanceRegistry command");
    }

    private static List<InstanceRegistryEmission> emissions(
            InstanceSnapshot snapshot,
            InstanceRegistryEvent event,
            InstanceRegistryReceipt receipt) {
        List<InstanceRegistryEmission> emissions = new ArrayList<>(List.of(
                new InstanceRegistryEmission(InstanceRegistryEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                new InstanceRegistryEmission(
                        InstanceRegistryEmissionKind.STATE,
                        ControlInstanceNames.stateKey(snapshot.instanceId()),
                        snapshot.wireValue(event.revision())),
                new InstanceRegistryEmission(InstanceRegistryEmissionKind.RESPONSE, receipt.commandId(), receipt.wireValue())));
        if (snapshot.status() == InstanceRegistryStatus.READY) {
            emissions.add(new InstanceRegistryEmission(InstanceRegistryEmissionKind.READY_INSTANCE, snapshot.instanceId().value(), snapshot.wireValue(event.revision())));
        } else if (snapshot.status() == InstanceRegistryStatus.DRAINING) {
            emissions.add(new InstanceRegistryEmission(InstanceRegistryEmissionKind.DRAINING_INSTANCE, snapshot.instanceId().value(), snapshot.wireValue(event.revision())));
        } else if (snapshot.status() == InstanceRegistryStatus.OFFLINE) {
            emissions.add(new InstanceRegistryEmission(InstanceRegistryEmissionKind.OFFLINE_INSTANCE, snapshot.instanceId().value(), snapshot.wireValue(event.revision())));
        }
        return List.copyOf(emissions);
    }

    private static InstanceRegistryDecision rejected(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            InstanceRegistryRecord currentRecord,
            InstanceRegistryRejectionReason reason) {
        return InstanceRegistryDecision.rejected(
                reason,
                currentRecord.revision(),
                currentRecord,
                command.envelope().traceEnvelope(),
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
    }
}

record StoredInstanceRegistryDecision(String payloadFingerprint, InstanceRegistryDecision decision) {
    StoredInstanceRegistryDecision {
        payloadFingerprint = ControlInstanceStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
