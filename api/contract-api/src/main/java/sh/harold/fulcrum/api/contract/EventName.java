package sh.harold.fulcrum.api.contract;

public record EventName(String value) {
    public EventName {
        value = Names.requireNonBlank(value, "eventName");
    }
}
