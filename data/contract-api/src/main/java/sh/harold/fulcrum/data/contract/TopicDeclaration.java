package sh.harold.fulcrum.data.contract;

import java.util.Objects;

public record TopicDeclaration(String name, TopicFamily family) {
    public TopicDeclaration {
        name = DeclarationNames.requireNonBlank(name, "name");
        family = Objects.requireNonNull(family, "family");
    }
}
