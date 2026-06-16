package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.CommandName;

import java.util.Objects;

public record CommandDeclaration(CommandName name, String payloadType, boolean revisionGuarded) {
    public CommandDeclaration {
        name = Objects.requireNonNull(name, "name");
        payloadType = DeclarationNames.requireNonBlank(payloadType, "payloadType");
    }
}
