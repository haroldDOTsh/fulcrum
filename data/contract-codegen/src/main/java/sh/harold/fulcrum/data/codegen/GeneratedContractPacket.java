package sh.harold.fulcrum.data.codegen;

import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.List;
import java.util.Objects;

public record GeneratedContractPacket(
        ContractDeclaration declaration,
        String declarationFingerprint,
        List<GeneratedArtifact> artifacts) {
    public GeneratedContractPacket {
        declaration = Objects.requireNonNull(declaration, "declaration");
        declarationFingerprint = CodegenNames.requireNonBlank(declarationFingerprint, "declarationFingerprint");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }

    public GeneratedArtifact artifact(String path) {
        return artifacts.stream()
                .filter(artifact -> artifact.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No generated artifact at " + path));
    }
}
