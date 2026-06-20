package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;

import java.util.Objects;

public record HostMenuReceipt(
        CommandId commandId,
        ContractName contractName,
        CommandName commandName,
        AggregateId aggregateId,
        String idempotencyKey) {
    public HostMenuReceipt {
        commandId = Objects.requireNonNull(commandId, "commandId");
        contractName = Objects.requireNonNull(contractName, "contractName");
        commandName = Objects.requireNonNull(commandName, "commandName");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        idempotencyKey = HostNames.requireNonBlank(idempotencyKey, "idempotencyKey");
    }
}
