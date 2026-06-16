package sh.harold.fulcrum.api.contract;

public record ContractName(String value) {
    public ContractName {
        value = Names.requireNonBlank(value, "contractName");
    }
}
