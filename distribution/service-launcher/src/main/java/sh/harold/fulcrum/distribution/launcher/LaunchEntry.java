package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

record LaunchEntry(
        LaunchRole role,
        String processFamily,
        String mainClass,
        List<RuntimeBindingRequirement> bindingRequirements
) {
    LaunchEntry {
        bindingRequirements = List.copyOf(bindingRequirements);
    }

    String command(ProfileDescriptor profile) {
        return "fulcrum --role=" + role.id()
                + " --profile=" + profile.profileId()
                + " --mode=run";
    }

    List<String> requiredBindings() {
        return bindingRequirements.stream()
                .map(RuntimeBindingRequirement::description)
                .toList();
    }

    List<String> missingBindings(RuntimeEnvironment environment) {
        return bindingRequirements.stream()
                .filter(requirement -> !requirement.satisfiedBy(environment))
                .map(requirement -> requirement.description() + " (" + requirement.name() + ")")
                .toList();
    }
}
