package sh.harold.fulcrum.control.fault;

public record FaultId(String value) {
    public FaultId {
        value = ControlFaultStrings.requireNonBlank(value, "faultId");
    }
}
