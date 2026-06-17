package sh.harold.fulcrum.host.paper;

import java.util.Objects;

public record AgonesGameServerSnapshot(
        String name,
        String namespace,
        String state,
        String rawJson) {
    public AgonesGameServerSnapshot {
        name = PaperArtifactNames.requireNonBlank(name, "name");
        namespace = PaperArtifactNames.requireNonBlank(namespace, "namespace");
        state = PaperArtifactNames.requireNonBlank(state, "state");
        rawJson = Objects.requireNonNull(rawJson, "rawJson");
    }
}
