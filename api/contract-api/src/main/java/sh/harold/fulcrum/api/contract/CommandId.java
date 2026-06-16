package sh.harold.fulcrum.api.contract;

public record CommandId(String value) {
    public CommandId {
        value = Names.requireNonBlank(value, "commandId");
    }
}
