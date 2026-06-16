package sh.harold.fulcrum.core.manifest;

import sh.harold.fulcrum.api.contract.ContractName;

import java.util.Objects;

public record ContractPin(ContractName contractName, String version) {
    public ContractPin {
        contractName = Objects.requireNonNull(contractName, "contractName");
        version = ManifestNames.requireNonBlank(version, "version");
    }
}
