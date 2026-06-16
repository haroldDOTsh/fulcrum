package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CapabilityDependencyGraph(
        List<CapabilityDescriptor> capabilities,
        Map<ContractName, CapabilityId> contractProviders,
        Map<CapabilityId, List<CapabilityId>> dependenciesByCapability) {
    public CapabilityDependencyGraph {
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        contractProviders = immutableMap(Objects.requireNonNull(contractProviders, "contractProviders"));
        dependenciesByCapability = immutableDependencyMap(
                Objects.requireNonNull(dependenciesByCapability, "dependenciesByCapability"));
    }

    public Optional<CapabilityId> providerOf(ContractName contractName) {
        return Optional.ofNullable(contractProviders.get(Objects.requireNonNull(contractName, "contractName")));
    }

    public List<CapabilityId> dependenciesFor(CapabilityId capabilityId) {
        return dependenciesByCapability.getOrDefault(Objects.requireNonNull(capabilityId, "capabilityId"), List.of());
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<CapabilityId, List<CapabilityId>> immutableDependencyMap(
            Map<CapabilityId, List<CapabilityId>> source) {
        Map<CapabilityId, List<CapabilityId>> copy = new LinkedHashMap<>();
        for (Map.Entry<CapabilityId, List<CapabilityId>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
