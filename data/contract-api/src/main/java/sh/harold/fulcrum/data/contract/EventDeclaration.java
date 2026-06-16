package sh.harold.fulcrum.data.contract;

import sh.harold.fulcrum.api.contract.EventName;

import java.util.List;
import java.util.Objects;

public record EventDeclaration(EventName name, String payloadType, List<FieldDeclaration> fields) {
    public EventDeclaration(EventName name, String payloadType) {
        this(name, payloadType, List.of());
    }

    public EventDeclaration {
        name = Objects.requireNonNull(name, "name");
        payloadType = DeclarationNames.requireNonBlank(payloadType, "payloadType");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
