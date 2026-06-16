package sh.harold.fulcrum.data.contract;

import java.util.List;
import java.util.Objects;

public record ProjectionDeclaration(String name, String relationName, List<FieldDeclaration> fields) {
    public ProjectionDeclaration {
        name = DeclarationNames.requireNonBlank(name, "name");
        relationName = DeclarationNames.requireNonBlank(relationName, "relationName");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
