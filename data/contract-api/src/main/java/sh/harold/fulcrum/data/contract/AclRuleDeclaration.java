package sh.harold.fulcrum.data.contract;

import java.util.List;
import java.util.Objects;

public record AclRuleDeclaration(String resource, List<String> producers, List<String> consumers) {
    public AclRuleDeclaration {
        resource = DeclarationNames.requireNonBlank(resource, "resource");
        producers = copyNonBlank(producers, "producers");
        consumers = copyNonBlank(consumers, "consumers");
    }

    private static List<String> copyNonBlank(List<String> values, String label) {
        return List.copyOf(Objects.requireNonNull(values, label).stream()
                .map(value -> DeclarationNames.requireNonBlank(value, label + " entry"))
                .toList());
    }
}
