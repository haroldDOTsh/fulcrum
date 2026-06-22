package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class OperatorCli {
    private static final int UNAVAILABLE = 69;
    private static final String RUN_PLAN_FILE = "run-plan.json";
    private static final String OPERATOR_HOME_ENV = "FULCRUM_OPERATOR_HOME";
    private static final String COMPOSE_FILE_NAME = "single-machine-full-engine.compose.yaml";
    private static final String COMPOSE_RESOURCE = "fulcrum/compose/" + COMPOSE_FILE_NAME;
    private static final String HELM_CHART_RESOURCE_ROOT = "fulcrum/helm/fulcrum";
    private static final String HELM_CHART_NAME = "fulcrum";
    private static final String DEFAULT_HELM_RELEASE = "fulcrum";
    private static final String DEFAULT_HELM_NAMESPACE = "fulcrum";
    private static final List<String> HELM_CHART_RESOURCES = List.of(
            "Chart.yaml",
            "values.yaml",
            "values-small-production.yaml",
            "values-large-production.yaml",
            "templates/_helpers.tpl",
            "templates/roles.yaml",
            "templates/stores.yaml");

    private final RuntimeEnvironment environment;
    private final ClassLoader classLoader;
    private final BundleRuntimeCommandRunner commandRunner;

    OperatorCli(RuntimeEnvironment environment, ClassLoader classLoader) {
        this(environment, classLoader, new ProcessBundleRuntimeCommandRunner());
    }

    OperatorCli(
            RuntimeEnvironment environment,
            ClassLoader classLoader,
            BundleRuntimeCommandRunner commandRunner) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    static boolean isOperatorCommand(String value) {
        return switch (value) {
            case "up", "status", "down", "cluster", "bundle", "dev", "author", "identity", "help" -> true;
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
                case "cluster" -> new ClusterOperatorCommands(environment, classLoader, commandRunner)
                        .run(slice(args), out, err);
                case "bundle" -> new BundleOperatorCommands(environment).run(slice(args), out, err);
                case "identity" -> new IdentityOperatorCommands().run(slice(args), out, err);
                case "author" -> new AuthorOperatorCommands().run(slice(args), out, err);
                case "dev" -> new DevOperatorCommands(environment, classLoader, commandRunner).run(slice(args), out, err);
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
            return upHelm(options, out);
        }

        SingleMachineTier tier = options.tier().orElse(SingleMachineTier.IN_MEMORY);
        Path composeFile = tier == SingleMachineTier.FULL_ENGINE
                ? singleMachineComposeFile(options.stateDir())
                : Path.of(COMPOSE_RESOURCE);
        OperatorRunPlan plan = OperatorRunPlan.forSingleMachine(
                options.profile(),
                tier,
                options.stateDir(),
                options.runFor(),
                composeFile);
        Path planFile = writePlan(plan);
        printPlan(plan, planFile, out);

        if (options.dryRun()) {
            out.println("dryRun=true");
            return FulcrumLauncher.OK;
        }

        if (tier == SingleMachineTier.FULL_ENGINE) {
            return startExternalPlan(plan, out);
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
        if (plan.isExternallySupervised() && plan.status().equals("running")) {
            return observeExternalPlan(plan, out);
        }
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
        OperatorRunPlan plan = maybePlan.orElseThrow();
        if (plan.isExternallySupervised() && shouldStopExternal(plan)) {
            BundleRuntimeCommandResult result = commandRunner.run(plan.teardownCommand(), workingDirectory());
            printCommandOutput(out, result.output());
            if (!result.succeeded()) {
                OperatorRunPlan failed = plan.withStatus("stop-failed");
                Path planFile = writePlan(failed);
                out.println("status=stop-failed");
                out.println("runPlan=" + planFile);
                out.println("exitCode=" + result.exitCode());
                return FulcrumLauncher.RUNTIME_FAILURE;
            }
        }
        OperatorRunPlan stopped = plan.withStatus("stopped");
        Path planFile = writePlan(stopped);
        out.println("status=stopped");
        out.println("runPlan=" + planFile);
        out.println("deploymentUnit=" + stopped.deploymentUnit());
        out.println("teardown=" + stopped.teardownCommandLine());
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

    private int upHelm(OperatorOptions options, PrintStream out) {
        Path chartDir = helmChartDir(options.stateDir());
        Path valuesFile = helmValuesFile(chartDir, options.profile());
        OperatorRunPlan plan = OperatorRunPlan.forHelm(
                options.profile(),
                options.stateDir(),
                options.release(),
                options.namespace(),
                chartDir,
                valuesFile);
        Path planFile = writePlan(plan);
        printPlan(plan, planFile, out);

        if (options.dryRun()) {
            out.println("dryRun=true");
            return FulcrumLauncher.OK;
        }
        return startExternalPlan(plan, out);
    }

    private void printPlan(OperatorRunPlan plan, Path planFile, PrintStream out) {
        out.println("Fulcrum operator plan written: " + planFile);
        out.println("profile=" + plan.profile());
        out.println("tier=" + plan.tier().orElse("none"));
        out.println("deploymentUnit=" + plan.deploymentUnit());
        out.println("entrypoint=" + plan.entrypoint());
        out.println("command=" + plan.commandLine());
    }

    private int startExternalPlan(OperatorRunPlan plan, PrintStream out) {
        BundleRuntimeCommandResult result = commandRunner.run(plan.command(), workingDirectory());
        printCommandOutput(out, result.output());
        if (!result.succeeded()) {
            OperatorRunPlan failed = plan.withStatus("failed");
            Path planFile = writePlan(failed);
            out.println("status=failed");
            out.println("runPlan=" + planFile);
            out.println("exitCode=" + result.exitCode());
            return FulcrumLauncher.RUNTIME_FAILURE;
        }
        OperatorRunPlan running = plan.withStatus("running");
        Path planFile = writePlan(running);
        out.println("status=running");
        out.println("runPlan=" + planFile);
        return FulcrumLauncher.OK;
    }

    private int observeExternalPlan(OperatorRunPlan plan, PrintStream out) {
        if (plan.statusCommand().isEmpty()) {
            out.println("runtimeStatus=unavailable");
            out.println("reason=missing-status-command");
            return FulcrumLauncher.RUNTIME_FAILURE;
        }
        BundleRuntimeCommandResult result = commandRunner.run(plan.statusCommand(), workingDirectory());
        printCommandOutput(out, result.output());
        out.println("runtimeStatus=" + (result.succeeded() ? "available" : "unavailable"));
        out.println("runtimeExitCode=" + result.exitCode());
        return result.succeeded() ? FulcrumLauncher.OK : FulcrumLauncher.RUNTIME_FAILURE;
    }

    private static boolean shouldStopExternal(OperatorRunPlan plan) {
        return !plan.status().equals("planned") && !plan.status().equals("stopped");
    }

    private Path singleMachineComposeFile(Path stateDir) {
        Optional<Path> operatorHome = environment.value(OPERATOR_HOME_ENV).map(Path::of);
        if (operatorHome.isPresent()) {
            Path composeFile = operatorHome.orElseThrow().resolve("compose").resolve(COMPOSE_FILE_NAME);
            if (!Files.isRegularFile(composeFile)) {
                throw new RuntimeConfigurationException(OPERATOR_HOME_ENV + " does not contain " + composeFile);
            }
            return composeFile;
        }
        Path composeFile = stateDir.resolve("compose").resolve(COMPOSE_FILE_NAME);
        materializeResource(COMPOSE_RESOURCE, composeFile, "Compose deployment unit");
        return composeFile;
    }

    private Path helmChartDir(Path stateDir) {
        Optional<Path> operatorHome = environment.value(OPERATOR_HOME_ENV).map(Path::of);
        if (operatorHome.isPresent()) {
            Path chartDir = operatorHome.orElseThrow().resolve("helm").resolve(HELM_CHART_NAME);
            if (!Files.isRegularFile(chartDir.resolve("Chart.yaml"))) {
                throw new RuntimeConfigurationException(OPERATOR_HOME_ENV + " does not contain " + chartDir);
            }
            return chartDir;
        }
        Path chartDir = stateDir.resolve("helm").resolve(HELM_CHART_NAME);
        for (String resource : HELM_CHART_RESOURCES) {
            materializeResource(
                    HELM_CHART_RESOURCE_ROOT + "/" + resource,
                    chartDir.resolve(resource),
                    "Helm chart resource");
        }
        return chartDir;
    }

    private static Path helmValuesFile(Path chartDir, DeploymentProfile profile) {
        Path valuesFile = chartDir.resolve("values-" + profile.id() + ".yaml");
        if (!Files.isRegularFile(valuesFile)) {
            throw new RuntimeConfigurationException("Helm values file is missing: " + valuesFile);
        }
        return valuesFile;
    }

    private void materializeResource(String resource, Path target, String label) {
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new RuntimeConfigurationException("Could not find " + label + " resource: " + resource);
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write " + label, exception);
        }
    }

    private static void printCommandOutput(PrintStream out, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        out.print(output);
        if (!output.endsWith(System.lineSeparator()) && !output.endsWith("\n")) {
            out.println();
        }
    }

    private static Path workingDirectory() {
        return Path.of(".").toAbsolutePath().normalize();
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
                "Usage: fulcrum <up|status|down|cluster|bundle|dev|author|identity> [options]",
                "",
                "Commands:",
                "  up       create a run plan and start or render the selected deployment unit",
                "  status   inspect the saved run plan",
                "  down     stop the deployment recorded in the saved run plan",
                "  cluster  local k3d/kind cluster lifecycle commands",
                "  bundle   declarative bundle install and reconcile commands",
                "  dev      author reload and one-click capability test loop (Phase 5)",
                "  author   author project commands (Phase 5)",
                "  identity install credential lifecycle commands",
                "");
    }

    private static String upUsage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum up [--profile=<profile>] [--tier=<tier>] [--state-dir=<path>] [--dry-run]",
                "                  [--release=<name>] [--namespace=<namespace>]",
                "",
                "Profiles: single-machine, small-production, large-production",
                "Tiers: in-memory, slim, full-engine",
                "full-engine renders the Compose deployment unit; in-memory and slim route to the in-image launcher.",
                "small-production and large-production wrap the packaged Helm chart.",
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
            Optional<Duration> runFor,
            String release,
            String namespace) {
        private static OperatorOptions parse(String[] args) {
            DeploymentProfile profile = DeploymentProfile.SINGLE_MACHINE;
            Optional<SingleMachineTier> tier = Optional.empty();
            Path stateDir = Path.of(".fulcrum");
            boolean dryRun = false;
            boolean help = false;
            Optional<Duration> runFor = Optional.empty();
            String release = DEFAULT_HELM_RELEASE;
            String namespace = DEFAULT_HELM_NAMESPACE;

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
                } else if (arg.startsWith("--release=")) {
                    release = nonBlank(arg.substring("--release=".length()), "--release");
                } else if (arg.equals("--release")) {
                    release = nonBlank(nextValue(args, ++index, "--release"), "--release");
                } else if (arg.startsWith("--namespace=")) {
                    namespace = nonBlank(arg.substring("--namespace=".length()), "--namespace");
                } else if (arg.equals("--namespace")) {
                    namespace = nonBlank(nextValue(args, ++index, "--namespace"), "--namespace");
                } else {
                    throw new IllegalArgumentException("Unknown fulcrum option: " + arg);
                }
            }
            if (profile != DeploymentProfile.SINGLE_MACHINE && tier.isPresent()) {
                throw new IllegalArgumentException("--tier is only supported by the single-machine profile");
            }
            return new OperatorOptions(profile, tier, stateDir, dryRun, help, runFor, release, namespace);
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
