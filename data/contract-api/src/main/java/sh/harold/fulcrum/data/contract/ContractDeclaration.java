package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.ContractName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ContractDeclaration(
        ContractName name,
        List<CommandDeclaration> commands,
        List<EventDeclaration> events,
        Optional<SnapshotDeclaration> snapshot,
        List<ProjectionDeclaration> projections,
        List<TopicDeclaration> topics,
        List<AclRuleDeclaration> aclRules) {
    public ContractDeclaration(
            ContractName name,
            List<CommandDeclaration> commands,
            List<EventDeclaration> events,
            List<TopicDeclaration> topics) {
        this(name, commands, events, Optional.empty(), List.of(), topics, List.of());
    }

    public ContractDeclaration(
            ContractName name,
            List<CommandDeclaration> commands,
            List<EventDeclaration> events,
            Optional<SnapshotDeclaration> snapshot,
            List<ProjectionDeclaration> projections,
            List<TopicDeclaration> topics) {
        this(name, commands, events, snapshot, projections, topics, List.of());
    }

    public ContractDeclaration {
        name = Objects.requireNonNull(name, "name");
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        aclRules = List.copyOf(Objects.requireNonNull(aclRules, "aclRules"));
    }
}
