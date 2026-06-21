package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class OperatorCli {
    private static final int UNAVAILABLE = 69;
    private static final String RUN_PLAN_FILE = "run-plan.json";

    private final RuntimeEnvironment environment;
    private final ClassLoader classLoader;

    OperatorCli(RuntimeEnvironment environment, ClassLoader classLoader) {
        this.environment = environment;
        this.classLoader = classLoader;
    }

    static boolean isOperatorCommand(String value) {
        return switch (value) {
            case "up", "status", "down", "bundle", "dev", "author", "identity", "help" -> true;
            default -> false;
        };
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        try {
            return switch (args[0]) {
                case "up" -> up(slice(args), out, err);
                case "status" -> status(slice(args), out);
                case "down" -> down(slice(args), out);
                case "bundle" -> new BundleOperatorCommands().run(slice(args), out, err);
                case "identity" -> new IdentityOperatorCommands().run(slice(args), out, err);
                case "author" -> new AuthorOperatorCommands().run(slice(args), out, err);
                case "dev" -> new DevOperatorCommands().run(slice(args), out, err);
                default -> throw new IllegalArgumentException("Unknown fulcrum command: " + args[0]);
            };
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            err.print(usage());
            return FulcrumLauncher.USAGE_ERROR;
        } catch (RuntimeConfigurationException exception) {
            err.println(exception.getMessage());
            return FulcrumLauncher.CONFIGURATION_BLOCKED;
        } catch (RuntimeException exception) {
            err.println(exception.getMessage());
            return FulcrumLauncher.RUNTIME_FAILURE;
        }
    }

    private int up(String[] args, PrintStream out, PrintStream err) {
        OperatorOptions options = OperatorOptions.parse(args);
        if (options.help()) {
            out.print(upUsage());
            return FulcrumLauncher.OK;
        }
        options.profile().loadDescriptor(classLoader);
        if (options.profile() != DeploymentProfile.SINGLE_MACHINE) {
            throw new IllegalArgumentException("fulcrum up is only supported for the single-machine profile; "
                    + "use the Fulcrum Helm chart for small-production or large-production.");
        }

        SingleMachineTier tier = options.tier().orElse(SingleMachineTier.IN_MEMORY);
        OperatorRunPlan plan = OperatorRunPlan.forSingleMachine(
                options.profile(),
                tier,
                options.stateDir(),
                options.runFor());
        Path planFile = writePlan(plan);
        out.println("Fulcrum operator plan written: " + planFile);
        out.println("profile=" + plan.profile());
        out.println("tier=" + plan.tier().orElse("none"));
        out.println("deploymentUnit=" + plan.deploymentUnit());
        out.println("entrypoint=" + plan.entrypoint());
        out.println("command=" + String.join(" ", plan.command()));

        if (options.dryRun()) {
            out.println("dryRun=true");
            return FulcrumLauncher.OK;
        }

        if (tier == SingleMachineTier.FULL_ENGINE) {
            throw new RuntimeConfigurationException("Compose execution requires the Phase 3 Compose deployment unit; "
                    + "rerun with --dry-run to render the saved plan.");
        }

        List<String> launcherArgs = new ArrayList<>();
        launcherArgs.add("--profile=single-machine");
        launcherArgs.add("--tier=" + tier.id());
        launcherArgs.add("--role=all");
        launcherArgs.add("--mode=run");
        options.runFor().ifPresent(duration -> launcherArgs.add("--run-for=" + duration));
        return new FulcrumLauncher(environment).run(
                launcherArgs.toArray(String[]::new),
                out,
                err);
    }

    private int status(String[] args, PrintStream out) {
        OperatorOptions options = OperatorOptions.parse(args);
        if (options.help()) {
            out.print(statusUsage());
            return FulcrumLauncher.OK;
        }
        Optional<OperatorRunPlan> maybePlan = readPlan(options.stateDir());
        if (maybePlan.isEmpty()) {
            out.println("status=missing");
            out.println("runPlan=" + options.stateDir().resolve(RUN_PLAN_FILE));
            return FulcrumLauncher.OK;
        }
        OperatorRunPlan plan = maybePlan.orElseThrow();
        out.println("status=" + plan.status());
        out.println("profile=" + plan.profile());
        out.println("tier=" + plan.tier().orElse("none"));
        out.println("deploymentUnit=" + plan.deploymentUnit());
        out.println("entrypoint=" + plan.entrypoint());
        return FulcrumLauncher.OK;
    }

    private int down(String[] args, PrintStream out) {
        OperatorOptions options = OperatorOptions.parse(args);
        if (options.help()) {
            out.print(downUsage());
            return FulcrumLauncher.OK;
        }
        Optional<OperatorRunPlan> maybePlan = readPlan(options.stateDir());
        if (maybePlan.isEmpty()) {
            out.println("status=missing");
            out.println("runPlan=" + options.stateDir().resolve(RUN_PLAN_FILE));
            return FulcrumLauncher.OK;
        }
        OperatorRunPlan stopped = maybePlan.orElseThrow().withStatus("stopped");
        Path planFile = writePlan(stopped);
        out.println("status=stopped");
        out.println("runPlan=" + planFile);
        out.println("deploymentUnit=" + stopped.deploymentUnit());
        out.println("teardown=" + stopped.teardownCommand());
        return FulcrumLauncher.OK;
    }

    private int unavailable(String command, String[] args, PrintStream out, PrintStream err) {
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("--help") || arg.equals("-h"))) {
            out.print(groupUsage(command));
            return FulcrumLauncher.OK;
        }
        err.println("fulcrum " + command + " is reserved by ADR-0029 but is not implemented in this phase slice.");
        err.println("The command surface is stable; the implementation lands in the governed install and author phases.");
        return UNAVAILABLE;
    }

    private Path writePlan(OperatorRunPlan plan) {
        try {
            Files.createDirectories(plan.stateDir());
            Path planFile = plan.stateDir().resolve(RUN_PLAN_FILE);
            Files.writeString(planFile, plan.toJson());
            return planFile;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write Fulcrum run plan", exception);
        }
    }

    private Optional<OperatorRunPlan> readPlan(Path stateDir) {
        Path planFile = stateDir.resolve(RUN_PLAN_FILE);
        if (!Files.exists(planFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(OperatorRunPlan.fromJson(stateDir, Files.readString(planFile)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read Fulcrum run plan", exception);
        }
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum <up|status|down|bundle|dev|author|identity> [options]",
                "",
                "Commands:",
                "  up       create a single-machine run plan and start or render it",
                "  status   inspect the saved run plan",
                "  down     stop the deployment recorded in the saved run plan",
                "  bundle   declarative bundle install and reconcile commands",
                "  dev      author reload loop (Phase 5)",
                "  author   author project commands (Phase 5)",
                "  identity install credential lifecycle commands",
                "");
    }

    private static String upUsage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum up [--profile=single-machine] [--tier=<tier>] [--state-dir=<path>] [--dry-run]",
                "",
                "Tiers: in-memory, slim, full-engine",
                "full-engine renders the Compose deployment unit; in-memory and slim route to the in-image launcher.",
                "");
    }

    private static String statusUsage() {
        return "Usage: fulcrum status [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String downUsage() {
        return "Usage: fulcrum down [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String groupUsage(String command) {
        return "Usage: fulcrum " + command + " [--help]" + System.lineSeparator()
                + "This command group is reserved by ADR-0029 for a later governed phase." + System.lineSeparator();
    }

    private record OperatorOptions(
            DeploymentProfile profile,
            Optional<SingleMachineTier> tier,
            Path stateDir,
            boolean dryRun,
            boolean help,
            Optional<Duration> runFor) {
        private static OperatorOptions parse(String[] args) {
            DeploymentProfile profile = DeploymentProfile.SINGLE_MACHINE;
            Optional<SingleMachineTier> tier = Optional.empty();
            Path stateDir = Path.of(".fulcrum");
            boolean dryRun = false;
            boolean help = false;
            Optional<Duration> runFor = Optional.empty();

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (arg.equals("--help") || arg.equals("-h")) {
                    help = true;
                } else if (arg.equals("--dry-run")) {
                    dryRun = true;
                } else if (arg.startsWith("--profile=")) {
                    profile = DeploymentProfile.fromId(arg.substring("--profile=".length()));
                } else if (arg.equals("--profile")) {
                    profile = DeploymentProfile.fromId(nextValue(args, ++index, "--profile"));
                } else if (arg.startsWith("--tier=")) {
                    tier = Optional.of(SingleMachineTier.fromId(arg.substring("--tier=".length())));
                } else if (arg.equals("--tier")) {
                    tier = Optional.of(SingleMachineTier.fromId(nextValue(args, ++index, "--tier")));
                } else if (arg.startsWith("--state-dir=")) {
                    stateDir = Path.of(nonBlank(arg.substring("--state-dir=".length()), "--state-dir"));
                } else if (arg.equals("--state-dir")) {
                    stateDir = Path.of(nonBlank(nextValue(args, ++index, "--state-dir"), "--state-dir"));
                } else if (arg.startsWith("--run-for=")) {
                    runFor = Optional.of(parseDuration(arg.substring("--run-for=".length()), "--run-for"));
                } else if (arg.equals("--run-for")) {
                    runFor = Optional.of(parseDuration(nextValue(args, ++index, "--run-for"), "--run-for"));
                } else {
                    throw new IllegalArgumentException("Unknown fulcrum option: " + arg);
                }
            }
            if (profile != DeploymentProfile.SINGLE_MACHINE && tier.isPresent()) {
                throw new IllegalArgumentException("--tier is only supported by the single-machine profile");
            }
            return new OperatorOptions(profile, tier, stateDir, dryRun, help, runFor);
        }

        private static String nextValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String nonBlank(String value, String option) {
            String checked = value.trim();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(option + " must not be blank");
            }
            return checked;
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
    }
}
