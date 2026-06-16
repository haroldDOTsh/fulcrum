package sh.harold.fulcrum.api.kernel;

public record MachineRef(String value) {
    public MachineRef {
        value = Ids.requireNonBlank(value, "machineRef");
    }
}
