package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

record LaunchPlan(ProfileDescriptor profile, LaunchMode mode, List<LaunchEntry> entries) {
    LaunchPlan {
        entries = List.copyOf(entries);
    }

    boolean canStart() {
        return missingBindings().isEmpty();
    }

    List<String> missingBindings() {
        return entries.stream()
                .flatMap(entry -> entry.requiredBindings().stream()
                        .map(binding -> entry.role().id() + ": " + binding))
                .distinct()
                .toList();
    }
}
