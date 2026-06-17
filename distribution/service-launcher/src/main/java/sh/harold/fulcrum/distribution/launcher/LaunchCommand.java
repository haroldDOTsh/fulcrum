package sh.harold.fulcrum.distribution.launcher;

import java.time.Duration;
import java.util.Optional;

record LaunchCommand(
        DeploymentProfile profile,
        LaunchRole role,
        LaunchMode mode,
        Optional<Duration> runFor,
        String probeHost,
        int probePort) {
    static LaunchCommand parse(String[] args) {
        DeploymentProfile profile = DeploymentProfile.SINGLE_MACHINE;
        LaunchRole role = LaunchRole.ALL;
        LaunchMode mode = LaunchMode.PLAN;
        Optional<Duration> runFor = Optional.empty();
        String probeHost = "0.0.0.0";
        int probePort = 8080;

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
            } else if (arg.startsWith("--run-for=")) {
                runFor = Optional.of(parseDuration(arg.substring("--run-for=".length()), "--run-for"));
            } else if (arg.equals("--run-for")) {
                runFor = Optional.of(parseDuration(nextValue(args, ++index, "--run-for"), "--run-for"));
            } else if (arg.startsWith("--probe-host=")) {
                probeHost = nonBlank(arg.substring("--probe-host=".length()), "--probe-host");
            } else if (arg.equals("--probe-host")) {
                probeHost = nonBlank(nextValue(args, ++index, "--probe-host"), "--probe-host");
            } else if (arg.startsWith("--probe-port=")) {
                probePort = parsePort(arg.substring("--probe-port=".length()));
            } else if (arg.equals("--probe-port")) {
                probePort = parsePort(nextValue(args, ++index, "--probe-port"));
            } else {
                throw new IllegalArgumentException("Unknown launcher argument: " + arg);
            }
        }

        return new LaunchCommand(profile, role, mode, runFor, probeHost, probePort);
    }

    private static String nextValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static Duration parseDuration(String value, String option) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(option + " must be a positive ISO-8601 duration");
            }
            return duration;
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(option + " must be an ISO-8601 duration, for example PT30S", exception);
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("--probe-port must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--probe-port must be a number", exception);
        }
    }

    private static String nonBlank(String value, String option) {
        String checked = value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(option + " must not be blank");
        }
        return checked;
    }
}
