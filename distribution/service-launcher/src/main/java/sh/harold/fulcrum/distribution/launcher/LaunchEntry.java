package sh.harold.fulcrum.distribution.launcher;

import java.util.List;
import java.util.Optional;

record LaunchEntry(
        LaunchRole role,
        String processFamily,
        String mainClass,
        List<RuntimeBindingRequirement> bindingRequirements
) {
    LaunchEntry {
        bindingRequirements = List.copyOf(bindingRequirements);
    }

    String command(ProfileDescriptor profile, Optional<SingleMachineTier> storageTier) {
        String command = "fulcrum --role=" + role.id()
                + " --profile=" + profile.profileId()
                + " --mode=run";
        if (storageTier.isPresent()) {
            command += " --tier=" + storageTier.orElseThrow().id();
        }
        return command;
    }

    List<String> requiredBindings(Optional<SingleMachineTier> storageTier) {
        return bindingRequirements.stream()
                .filter(requirement -> requirement.requiredFor(storageTier))
                .map(RuntimeBindingRequirement::description)
                .toList();
    }

    List<String> missingBindings(RuntimeEnvironment environment, Optional<SingleMachineTier> storageTier) {
        return bindingRequirements.stream()
                .filter(requirement -> requirement.requiredFor(storageTier))
                .filter(requirement -> !requirement.satisfiedBy(environment))
                .map(requirement -> requirement.description() + " (" + requirement.name() + ")")
                .toList();
    }
}
