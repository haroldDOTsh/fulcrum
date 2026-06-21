package sh.harold.fulcrum.distribution.launcher;

import java.util.List;
import java.util.Optional;

record LaunchPlan(
        ProfileDescriptor profile,
        LaunchMode mode,
        Optional<SingleMachineTier> storageTier,
        List<LaunchEntry> entries) {
    LaunchPlan {
        storageTier = storageTier == null ? Optional.empty() : storageTier;
        entries = List.copyOf(entries);
    }

    boolean canStart() {
        return canStart(RuntimeEnvironment.system());
    }

    boolean canStart(RuntimeEnvironment environment) {
        return missingBindings(environment).isEmpty();
    }

    List<String> missingBindings() {
        return missingBindings(RuntimeEnvironment.system());
    }

    List<String> missingBindings(RuntimeEnvironment environment) {
        return entries.stream()
                .flatMap(entry -> entry.missingBindings(environment, storageTier).stream()
                        .map(binding -> entry.role().id() + ": " + binding))
                .distinct()
                .toList();
    }
}
