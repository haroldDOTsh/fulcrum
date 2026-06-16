package sh.harold.fulcrum.data.contract;

import java.util.Objects;

public record FieldDeclaration(String name, FieldType type, boolean nullable) {
    public FieldDeclaration(String name, FieldType type) {
        this(name, type, false);
    }

    public FieldDeclaration {
        name = DeclarationNames.requireNonBlank(name, "name");
        type = Objects.requireNonNull(type, "type");
    }
}
