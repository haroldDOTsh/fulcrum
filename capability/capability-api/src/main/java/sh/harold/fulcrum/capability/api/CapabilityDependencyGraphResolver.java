package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CapabilityDependencyGraphResolver {
    private CapabilityDependencyGraphResolver() {
    }

    public static CapabilityValidationResult validate(List<CapabilityDescriptor> descriptors) {
        List<CapabilityDescriptor> checkedDescriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        List<CapabilityValidationError> errors = new ArrayList<>();
        for (CapabilityDescriptor descriptor : checkedDescriptors) {
            for (CapabilityValidationError error : CapabilityManifestValidator.validate(descriptor).errors()) {
                errors.add(new CapabilityValidationError(
                        "graph.manifest." + error.code(),
                        descriptor.capabilityId().value() + ": " + error.detail()));
            }
        }

        addDuplicateCapabilityErrors(errors, checkedDescriptors);
        Map<ContractName, CapabilityId> providers = contractProviders(errors, checkedDescriptors);
        Map<CapabilityId, List<CapabilityId>> dependencies = dependenciesByCapability(checkedDescriptors, providers, errors);
        addCycleErrors(errors, dependencies);
        return new CapabilityValidationResult(errors);
    }

    public static CapabilityDependencyGraph resolve(List<CapabilityDescriptor> descriptors) {
        List<CapabilityDescriptor> checkedDescriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        CapabilityValidationResult validationResult = validate(checkedDescriptors);
        if (!validationResult.valid()) {
            throw new IllegalArgumentException("invalid capability dependency graph: " + validationResult.errors());
        }
        Map<ContractName, CapabilityId> providers = contractProviders(new ArrayList<>(), checkedDescriptors);
        return new CapabilityDependencyGraph(
                checkedDescriptors,
                providers,
                dependenciesByCapability(checkedDescriptors, providers, new ArrayList<>()));
    }

    private static void addDuplicateCapabilityErrors(
            List<CapabilityValidationError> errors,
            List<CapabilityDescriptor> descriptors) {
        Set<CapabilityId> seen = new HashSet<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            if (!seen.add(descriptor.capabilityId())) {
                errors.add(new CapabilityValidationError(
                        "graph.capability.duplicate",
                        descriptor.capabilityId().value()));
            }
        }
    }

    private static Map<ContractName, CapabilityId> contractProviders(
            List<CapabilityValidationError> errors,
            List<CapabilityDescriptor> descriptors) {
        Map<ContractName, CapabilityId> providers = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            for (ContractDeclaration declaration : descriptor.declaredContracts()) {
                CapabilityId previous = providers.putIfAbsent(declaration.name(), descriptor.capabilityId());
                if (previous != null && !previous.equals(descriptor.capabilityId())) {
                    errors.add(new CapabilityValidationError(
                            "graph.contract.provider.duplicate",
                            declaration.name().value() + " provided by "
                                    + previous.value() + " and " + descriptor.capabilityId().value()));
                }
            }
        }
        return providers;
    }

    private static Map<CapabilityId, List<CapabilityId>> dependenciesByCapability(
            List<CapabilityDescriptor> descriptors,
            Map<ContractName, CapabilityId> providers,
            List<CapabilityValidationError> errors) {
        Map<CapabilityId, List<CapabilityId>> dependencies = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            LinkedHashSet<CapabilityId> capabilityDependencies = new LinkedHashSet<>();
            for (ContractName requiredContract : descriptor.requiredContracts()) {
                CapabilityId provider = providers.get(requiredContract);
                if (provider == null) {
                    errors.add(new CapabilityValidationError(
                            "graph.contract.missing",
                            descriptor.capabilityId().value() + " requires " + requiredContract.value()));
                } else if (!provider.equals(descriptor.capabilityId())) {
                    capabilityDependencies.add(provider);
                }
            }
            dependencies.put(descriptor.capabilityId(), List.copyOf(capabilityDependencies));
        }
        return dependencies;
    }

    private static void addCycleErrors(
            List<CapabilityValidationError> errors,
            Map<CapabilityId, List<CapabilityId>> dependencies) {
        Map<CapabilityId, VisitState> states = new HashMap<>();
        Set<String> reportedCycles = new HashSet<>();
        for (CapabilityId capabilityId : dependencies.keySet()) {
            detectCycles(capabilityId, dependencies, states, new ArrayList<>(), reportedCycles, errors);
        }
    }

    private static void detectCycles(
            CapabilityId capabilityId,
            Map<CapabilityId, List<CapabilityId>> dependencies,
            Map<CapabilityId, VisitState> states,
            List<CapabilityId> path,
            Set<String> reportedCycles,
            List<CapabilityValidationError> errors) {
        VisitState state = states.get(capabilityId);
        if (state == VisitState.VISITING) {
            int cycleStart = path.indexOf(capabilityId);
            if (cycleStart >= 0) {
                List<CapabilityId> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                cycle.add(capabilityId);
                String detail = cycle.stream().map(CapabilityId::value).collect(Collectors.joining(" -> "));
                if (reportedCycles.add(detail)) {
                    errors.add(new CapabilityValidationError("graph.capability.cycle", detail));
                }
            }
            return;
        }
        if (state == VisitState.VISITED) {
            return;
        }

        states.put(capabilityId, VisitState.VISITING);
        path.add(capabilityId);
        for (CapabilityId dependency : dependencies.getOrDefault(capabilityId, List.of())) {
            detectCycles(dependency, dependencies, states, path, reportedCycles, errors);
        }
        path.remove(path.size() - 1);
        states.put(capabilityId, VisitState.VISITED);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
