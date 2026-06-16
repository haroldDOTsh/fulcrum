package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public record CapabilityEnablementState(
        CapabilityScope scope,
        Map<CapabilityId, CapabilityBinding> bindings) {
    public CapabilityEnablementState {
        scope = Objects.requireNonNull(scope, "scope");
        Map<CapabilityId, CapabilityBinding> checked = new LinkedHashMap<>();
        Objects.requireNonNull(bindings, "bindings").entrySet().stream()
                .sorted(Map.Entry.comparingByKey((left, right) -> left.value().compareTo(right.value())))
                .forEach(entry -> {
                    CapabilityId key = Objects.requireNonNull(entry.getKey(), "capabilityId");
                    CapabilityBinding binding = Objects.requireNonNull(entry.getValue(), "binding");
                    if (!key.equals(binding.capabilityId())) {
                        throw new IllegalArgumentException("binding key must match capabilityId");
                    }
                    checked.put(key, binding);
                });
        bindings = Collections.unmodifiableMap(checked);
    }

    public static CapabilityEnablementState empty(CapabilityScope scope) {
        return new CapabilityEnablementState(scope, Map.of());
    }

    public Optional<CapabilityBinding> binding(CapabilityId capabilityId) {
        return Optional.ofNullable(bindings.get(capabilityId));
    }

    public CapabilityEnablementState withBinding(CapabilityBinding binding) {
        Map<CapabilityId, CapabilityBinding> next = new LinkedHashMap<>(bindings);
        next.put(binding.capabilityId(), binding);
        return new CapabilityEnablementState(scope, next);
    }

    public String wireValue(Revision revision) {
        String enabled = bindings.values().stream()
                .map(CapabilityBinding::wireValue)
                .collect(Collectors.joining(","));
        return "scope=" + scope.value()
                + "|revision=" + revision.value()
                + "|bindings=" + enabled;
    }
}
