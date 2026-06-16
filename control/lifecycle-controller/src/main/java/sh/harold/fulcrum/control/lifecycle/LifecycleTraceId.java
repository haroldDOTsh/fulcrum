package sh.harold.fulcrum.control.lifecycle;

public record LifecycleTraceId(String value) {
    public LifecycleTraceId {
        value = ControlLifecycleStrings.requireNonBlank(value, "lifecycleTraceId");
    }
}
