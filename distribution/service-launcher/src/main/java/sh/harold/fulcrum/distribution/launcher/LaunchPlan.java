package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

record LaunchPlan(ProfileDescriptor profile, LaunchMode mode, List<LaunchEntry> entries) {
    LaunchPlan {
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
                .flatMap(entry -> entry.missingBindings(environment).stream()
                        .map(binding -> entry.role().id() + ": " + binding))
                .distinct()
                .toList();
    }
}
