package sh.harold.fulcrum.api.contract;

public record PrincipalId(String value) {
    public PrincipalId {
        value = Names.requireNonBlank(value, "principalId");
    }
}
