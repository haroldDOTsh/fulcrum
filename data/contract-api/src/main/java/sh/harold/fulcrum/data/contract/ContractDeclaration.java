package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.ContractName;

import java.util.List;
import java.util.Objects;

public record ContractDeclaration(
        ContractName name,
        List<CommandDeclaration> commands,
        List<EventDeclaration> events,
        List<TopicDeclaration> topics) {
    public ContractDeclaration {
        name = Objects.requireNonNull(name, "name");
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
    }
}
