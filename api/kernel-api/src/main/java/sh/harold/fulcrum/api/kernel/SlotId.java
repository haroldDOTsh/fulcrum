package sh.harold.fulcrum.api.kernel;

public record SlotId(String value) {
    public SlotId {
        value = Ids.requireNonBlank(value, "slotId");
    }
}
