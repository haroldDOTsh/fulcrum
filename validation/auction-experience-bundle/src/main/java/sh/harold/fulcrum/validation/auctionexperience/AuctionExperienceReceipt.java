package sh.harold.fulcrum.validation.auctionexperience;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;

import java.util.Objects;

public record AuctionExperienceReceipt(
        CommandId commandId,
        ContractName contractName,
        CommandName commandName,
        AggregateId aggregateId,
        String correlationId) {
    public AuctionExperienceReceipt {
        commandId = Objects.requireNonNull(commandId, "commandId");
        contractName = Objects.requireNonNull(contractName, "contractName");
        commandName = Objects.requireNonNull(commandName, "commandName");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        correlationId = Names.requireNonBlank(correlationId, "correlationId");
    }
}
