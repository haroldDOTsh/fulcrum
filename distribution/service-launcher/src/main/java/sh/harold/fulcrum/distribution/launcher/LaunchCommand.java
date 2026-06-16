package sh.harold.fulcrum.distribution.launcher;

record LaunchCommand(DeploymentProfile profile, LaunchRole role, LaunchMode mode) {
    static LaunchCommand parse(String[] args) {
        DeploymentProfile profile = DeploymentProfile.SINGLE_MACHINE;
        LaunchRole role = LaunchRole.ALL;
        LaunchMode mode = LaunchMode.PLAN;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg.startsWith("--profile=")) {
                profile = DeploymentProfile.fromId(arg.substring("--profile=".length()));
            } else if (arg.equals("--profile")) {
                profile = DeploymentProfile.fromId(nextValue(args, ++index, "--profile"));
            } else if (arg.startsWith("--role=")) {
                role = LaunchRole.fromId(arg.substring("--role=".length()));
            } else if (arg.equals("--role")) {
                role = LaunchRole.fromId(nextValue(args, ++index, "--role"));
            } else if (arg.startsWith("--mode=")) {
                mode = LaunchMode.fromId(arg.substring("--mode=".length()));
            } else if (arg.equals("--mode")) {
                mode = LaunchMode.fromId(nextValue(args, ++index, "--mode"));
            } else {
                throw new IllegalArgumentException("Unknown launcher argument: " + arg);
            }
        }

        return new LaunchCommand(profile, role, mode);
    }

    private static String nextValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
