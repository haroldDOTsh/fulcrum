package sh.harold.fulcrum.api.contract;

public record CommandName(String value) {
    public CommandName {
        value = Names.requireNonBlank(value, "commandName");
    }
}
