package sh.harold.fulcrum.api.contract;

public record AggregateId(String value) {
    public AggregateId {
        value = Names.requireNonBlank(value, "aggregateId");
    }
}
