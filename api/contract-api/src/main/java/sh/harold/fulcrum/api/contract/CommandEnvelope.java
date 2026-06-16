package sh.harold.fulcrum.api.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CommandEnvelope<T extends CommandPayload>(
        CommandId commandId,
        IdempotencyKey idempotencyKey,
        PrincipalId principalId,
        AggregateId aggregateId,
        ContractName contractName,
        CommandName commandName,
        TraceEnvelope traceEnvelope,
        Optional<Instant> deadlineAt,
        T payload) {
    public CommandEnvelope {
        commandId = Objects.requireNonNull(commandId, "commandId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        principalId = Objects.requireNonNull(principalId, "principalId");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        contractName = Objects.requireNonNull(contractName, "contractName");
        commandName = Objects.requireNonNull(commandName, "commandName");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        deadlineAt = deadlineAt == null ? Optional.empty() : deadlineAt;
        payload = Objects.requireNonNull(payload, "payload");
    }
}
