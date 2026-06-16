package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

record LaunchEntry(
        LaunchRole role,
        String processFamily,
        String mainClass,
        List<String> requiredBindings
) {
    LaunchEntry {
        requiredBindings = List.copyOf(requiredBindings);
    }

    String command(ProfileDescriptor profile) {
        return "fulcrum --role=" + role.id()
                + " --profile=" + profile.profileId()
                + " --mode=run";
    }
}
