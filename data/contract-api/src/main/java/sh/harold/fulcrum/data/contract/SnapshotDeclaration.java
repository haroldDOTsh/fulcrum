package sh.harold.fulcrum.data.contract;

import java.util.List;
import java.util.Objects;

public record SnapshotDeclaration(String payloadType, List<FieldDeclaration> fields) {
    public SnapshotDeclaration {
        payloadType = DeclarationNames.requireNonBlank(payloadType, "payloadType");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
