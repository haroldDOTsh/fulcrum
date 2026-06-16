package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.EventName;

import java.util.Objects;

public record EventDeclaration(EventName name, String payloadType) {
    public EventDeclaration {
        name = Objects.requireNonNull(name, "name");
        payloadType = DeclarationNames.requireNonBlank(payloadType, "payloadType");
    }
}
