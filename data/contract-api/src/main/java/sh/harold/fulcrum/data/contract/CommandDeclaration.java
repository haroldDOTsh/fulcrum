package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.CommandName;

import java.util.List;
import java.util.Objects;

public record CommandDeclaration(CommandName name, String payloadType, List<FieldDeclaration> fields, boolean revisionGuarded) {
    public CommandDeclaration(CommandName name, String payloadType, boolean revisionGuarded) {
        this(name, payloadType, List.of(), revisionGuarded);
    }

    public CommandDeclaration {
        name = Objects.requireNonNull(name, "name");
        payloadType = DeclarationNames.requireNonBlank(payloadType, "payloadType");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
