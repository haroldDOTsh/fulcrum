package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ClusterOperatorCommands {
    private static final String PLAN_FILE = "cluster-plan.json";
    private static final String OPERATOR_HOME_ENV = "FULCRUM_OPERATOR_HOME";
    private static final String HELM_CHART_RESOURCE_ROOT = "fulcrum/helm/fulcrum";
    private static final String HELM_CHART_NAME = "fulcrum";
    private static final List<String> HELM_CHART_RESOURCES = List.of(
            "Chart.yaml",
            "values.yaml",
            "values-small-production.yaml",
            "values-large-production.yaml",
            "templates/_helpers.tpl",
            "templates/roles.yaml",
            "templates/stores.yaml");
    private static final Set<String> FLAGS = Set.of("dry-run", "help");
    private static final Set<String> OPTIONS = Set.of(
            "state-dir",
            "provider",
            "name",
            "profile",
            "namespace",
            "api-port",
            "minecraft-port",
            "k3d-image");

    private final RuntimeEnvironment environment;
    private final ClassLoader classLoader;
    private final BundleRuntimeCommandRunner commandRunner;

    ClusterOperatorCommands(
            RuntimeEnvironment environment,
            ClassLoader classLoader,
            BundleRuntimeCommandRunner commandRunner) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "up" -> up(slice(args), out);
            case "status" -> status(slice(args), out);
            case "down" -> down(slice(args), out);
            default -> throw new IllegalArgumentException("Unknown fulcrum cluster command: " + args[0]);
        };
    }

    private int up(String[] args, PrintStream out) {
        ClusterOptions options = ClusterOptions.parse(args);
        if (options.help()) {
            out.print(upUsage());
            return FulcrumLauncher.OK;
        }
        Path chartDir = helmChartDir(options.stateDir());
        ClusterRunPlan plan = ClusterRunPlan.create(options, chartDir);
        Path planFile = writePlan(plan);
        printPlan(plan, planFile, out);
        if (options.dryRun()) {
            out.println("dryRun=true");
            return FulcrumLauncher.OK;
        }

        if (plan.provider().equals("kind")) {
            writeText(plan.kindConfigFile(), plan.kindConfig());
        }
        BundleRuntimeCommandResult create = commandRunner.run(plan.createCommand(), workingDirectory());
        printCommandOutput(out, create.output());
        if (!create.succeeded()) {
            writePlan(plan.withStatus("failed"));
            out.println("status=failed");
            out.println("exitCode=" + create.exitCode());
            return FulcrumLauncher.RUNTIME_FAILURE;
        }

        if (plan.provider().equals("k3d")) {
            BundleRuntimeCommandResult kubeconfig = commandRunner.run(plan.kubeconfigCommand(), workingDirectory());
            if (!kubeconfig.succeeded()) {
                writePlan(plan.withStatus("failed"));
                out.println("status=failed");
                out.println("exitCode=" + kubeconfig.exitCode());
                return FulcrumLauncher.RUNTIME_FAILURE;
            }
            writeText(plan.kubeconfigFile(), kubeconfig.output());
        }

        BundleRuntimeCommandResult wait = commandRunner.run(plan.nodeWaitCommand(), workingDirectory());
        printCommandOutput(out, wait.output());
        if (!wait.succeeded()) {
            writePlan(plan.withStatus("failed"));
            out.println("status=failed");
            out.println("exitCode=" + wait.exitCode());
            return FulcrumLauncher.RUNTIME_FAILURE;
        }

        BundleRuntimeCommandResult install = commandRunner.run(plan.installCommand(), workingDirectory());
        printCommandOutput(out, install.output());
        if (!install.succeeded()) {
            writePlan(plan.withStatus("failed"));
            out.println("status=failed");
            out.println("exitCode=" + install.exitCode());
            return FulcrumLauncher.RUNTIME_FAILURE;
        }

        Path runningPlan = writePlan(plan.withStatus("running"));
        out.println("status=running");
        out.println("runPlan=" + runningPlan);
        return FulcrumLauncher.OK;
    }

    private int status(String[] args, PrintStream out) {
        ClusterOptions options = ClusterOptions.parse(args);
        if (options.help()) {
            out.print(statusUsage());
            return FulcrumLauncher.OK;
        }
        Optional<ClusterRunPlan> maybePlan = readPlan(options.stateDir());
        if (maybePlan.isEmpty()) {
            out.println("status=missing");
            out.println("runPlan=" + options.stateDir().resolve(PLAN_FILE));
            return FulcrumLauncher.OK;
        }
        ClusterRunPlan plan = maybePlan.orElseThrow();
        out.println("status=" + plan.status());
        out.println("provider=" + plan.provider());
        out.println("cluster=" + plan.name());
        out.println("kubeconfig=" + plan.kubeconfigFile());
        if (!plan.status().equals("running")) {
            return FulcrumLauncher.OK;
        }
        BundleRuntimeCommandResult cluster = commandRunner.run(plan.statusCommand(), workingDirectory());
        printCommandOutput(out, cluster.output());
        BundleRuntimeCommandResult helm = commandRunner.run(plan.helmStatusCommand(), workingDirectory());
        printCommandOutput(out, helm.output());
        out.println("clusterStatus=" + (cluster.succeeded() && helm.succeeded() ? "available" : "unavailable"));
        return cluster.succeeded() && helm.succeeded() ? FulcrumLauncher.OK : FulcrumLauncher.RUNTIME_FAILURE;
    }

    private int down(String[] args, PrintStream out) {
        ClusterOptions options = ClusterOptions.parse(args);
        if (options.help()) {
            out.print(downUsage());
            return FulcrumLauncher.OK;
        }
        Optional<ClusterRunPlan> maybePlan = readPlan(options.stateDir());
        if (maybePlan.isEmpty()) {
            out.println("status=missing");
            out.println("runPlan=" + options.stateDir().resolve(PLAN_FILE));
            return FulcrumLauncher.OK;
        }
        ClusterRunPlan plan = maybePlan.orElseThrow();
        if (!plan.status().equals("stopped")) {
            BundleRuntimeCommandResult result = commandRunner.run(plan.downCommand(), workingDirectory());
            printCommandOutput(out, result.output());
            if (!result.succeeded()) {
                writePlan(plan.withStatus("stop-failed"));
                out.println("status=stop-failed");
                out.println("exitCode=" + result.exitCode());
                return FulcrumLauncher.RUNTIME_FAILURE;
            }
        }
        Path stoppedPlan = writePlan(plan.withStatus("stopped"));
        out.println("status=stopped");
        out.println("runPlan=" + stoppedPlan);
        return FulcrumLauncher.OK;
    }

    private Path writePlan(ClusterRunPlan plan) {
        try {
            Files.createDirectories(plan.stateDir());
            Path planFile = plan.stateDir().resolve(PLAN_FILE);
            Files.writeString(planFile, plan.toJson());
            return planFile;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write Fulcrum cluster plan", exception);
        }
    }

    private Optional<ClusterRunPlan> readPlan(Path stateDir) {
        Path planFile = stateDir.resolve(PLAN_FILE);
        if (!Files.exists(planFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClusterRunPlan.fromJson(stateDir, Files.readString(planFile)));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read Fulcrum cluster plan", exception);
        }
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
        Path chartDir = stateDir.resolve("cluster").resolve("helm").resolve(HELM_CHART_NAME);
        for (String resource : HELM_CHART_RESOURCES) {
            materializeResource(
                    HELM_CHART_RESOURCE_ROOT + "/" + resource,
                    chartDir.resolve(resource),
                    "Helm chart resource");
        }
        return chartDir;
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

    private static void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write " + path, exception);
        }
    }

    private static void printPlan(ClusterRunPlan plan, Path planFile, PrintStream out) {
        out.println("Fulcrum cluster plan written: " + planFile);
        out.println("provider=" + plan.provider());
        out.println("cluster=" + plan.name());
        out.println("profile=" + plan.profile());
        out.println("namespace=" + plan.namespace());
        out.println("kubeconfig=" + plan.kubeconfigFile());
        out.println("create=" + String.join(" ", plan.createCommand()));
        out.println("install=" + String.join(" ", plan.installCommand()));
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

    private static String[] slice(String[] args) {
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum cluster <up|status|down> [options]",
                "",
                "Commands:",
                "  up      create a local k3d/kind cluster and install Fulcrum with Helm",
                "  status  inspect the saved local cluster plan",
                "  down    delete the generated local cluster",
                "");
    }

    private static String upUsage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum cluster up [--provider=k3d|kind] [--name=<name>] [--state-dir=<path>]",
                "                          [--profile=small-production|large-production] [--dry-run]",
                "                          [--api-port=<port>] [--minecraft-port=<port>]",
                "");
    }

    private static String statusUsage() {
        return "Usage: fulcrum cluster status [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String downUsage() {
        return "Usage: fulcrum cluster down [--state-dir=<path>]" + System.lineSeparator();
    }

    private record ClusterOptions(
            Path stateDir,
            String provider,
            String name,
            String profile,
            String namespace,
            String apiPort,
            String minecraftPort,
            String k3dImage,
            boolean dryRun,
            boolean help) {
        static ClusterOptions parse(String[] args) {
            OperatorArguments parsed = OperatorArguments.parse(args, FLAGS);
            Set<String> allowed = new java.util.HashSet<>(FLAGS);
            allowed.addAll(OPTIONS);
            parsed.rejectUnknown(allowed);
            String provider = parsed.value("provider").orElse("k3d").toLowerCase(java.util.Locale.ROOT);
            if (!provider.equals("k3d") && !provider.equals("kind")) {
                throw new IllegalArgumentException("--provider must be k3d or kind");
            }
            String profile = parsed.value("profile").orElse(DeploymentProfile.SMALL_PRODUCTION.id());
            if (!profile.equals(DeploymentProfile.SMALL_PRODUCTION.id())
                    && !profile.equals(DeploymentProfile.LARGE_PRODUCTION.id())) {
                throw new IllegalArgumentException("--profile must be small-production or large-production");
            }
            return new ClusterOptions(
                    Path.of(parsed.value("state-dir").orElse(".fulcrum")),
                    provider,
                    parsed.value("name").orElse("fulcrum-local"),
                    profile,
                    parsed.value("namespace").orElse("fulcrum"),
                    parsed.value("api-port").orElse("16443"),
                    parsed.value("minecraft-port").orElse("25565"),
                    parsed.value("k3d-image").orElse("rancher/k3s:v1.34.7-k3s1"),
                    parsed.flag("dry-run"),
                    parsed.flag("help"));
        }
    }

    private record ClusterRunPlan(
            String schema,
            String status,
            Path stateDir,
            String provider,
            String name,
            String profile,
            String namespace,
            String apiPort,
            String minecraftPort,
            String k3dImage,
            Path kubeconfigFile,
            Path kindConfigFile,
            Path chartDir) {
        static ClusterRunPlan create(ClusterOptions options, Path chartDir) {
            Path clusterDir = options.stateDir().resolve("cluster");
            return new ClusterRunPlan(
                    "fulcrum.cluster-run-plan/v1",
                    "planned",
                    options.stateDir(),
                    options.provider(),
                    options.name(),
                    options.profile(),
                    options.namespace(),
                    options.apiPort(),
                    options.minecraftPort(),
                    options.k3dImage(),
                    clusterDir.resolve("kubeconfig.yaml"),
                    clusterDir.resolve("kind-config.yaml"),
                    chartDir);
        }

        ClusterRunPlan withStatus(String newStatus) {
            return new ClusterRunPlan(
                    schema,
                    newStatus,
                    stateDir,
                    provider,
                    name,
                    profile,
                    namespace,
                    apiPort,
                    minecraftPort,
                    k3dImage,
                    kubeconfigFile,
                    kindConfigFile,
                    chartDir);
        }

        List<String> createCommand() {
            if (provider.equals("k3d")) {
                return List.of(
                        "k3d",
                        "cluster",
                        "create",
                        name,
                        "--image",
                        k3dImage,
                        "--api-port",
                        "127.0.0.1:" + apiPort,
                        "-p",
                        minecraftPort + ":25565@loadbalancer",
                        "--wait");
            }
            return List.of(
                    "kind",
                    "create",
                    "cluster",
                    "--name",
                    name,
                    "--config",
                    kindConfigFile.toString(),
                    "--kubeconfig",
                    kubeconfigFile.toString(),
                    "--wait",
                    "240s");
        }

        List<String> kubeconfigCommand() {
            return List.of("k3d", "kubeconfig", "get", name);
        }

        List<String> nodeWaitCommand() {
            return List.of(
                    "kubectl",
                    "--kubeconfig",
                    kubeconfigFile.toString(),
                    "wait",
                    "--for=condition=Ready",
                    "node",
                    "--all",
                    "--timeout=240s");
        }

        List<String> installCommand() {
            return List.of(
                    "helm",
                    "--kubeconfig",
                    kubeconfigFile.toString(),
                    "upgrade",
                    "--install",
                    "fulcrum",
                    chartDir.toString(),
                    "--namespace",
                    namespace,
                    "--create-namespace",
                    "--values",
                    chartDir.resolve("values-" + profile + ".yaml").toString(),
                    "--wait",
                    "--timeout",
                    "10m");
        }

        List<String> statusCommand() {
            if (provider.equals("k3d")) {
                return List.of("k3d", "cluster", "get", name);
            }
            return List.of("kind", "get", "clusters");
        }

        List<String> helmStatusCommand() {
            return List.of(
                    "helm",
                    "--kubeconfig",
                    kubeconfigFile.toString(),
                    "status",
                    "fulcrum",
                    "--namespace",
                    namespace);
        }

        List<String> downCommand() {
            if (provider.equals("k3d")) {
                return List.of("k3d", "cluster", "delete", name);
            }
            return List.of("kind", "delete", "cluster", "--name", name);
        }

        String kindConfig() {
            return "kind: Cluster" + System.lineSeparator()
                    + "apiVersion: kind.x-k8s.io/v1alpha4" + System.lineSeparator()
                    + "nodes:" + System.lineSeparator()
                    + "  - role: control-plane" + System.lineSeparator()
                    + "    extraPortMappings:" + System.lineSeparator()
                    + "      - containerPort: 25565" + System.lineSeparator()
                    + "        hostPort: " + minecraftPort + System.lineSeparator()
                    + "        protocol: TCP" + System.lineSeparator();
        }

        String toJson() {
            String line = System.lineSeparator();
            return "{" + line
                    + "  \"schema\": \"" + escape(schema) + "\"," + line
                    + "  \"status\": \"" + escape(status) + "\"," + line
                    + "  \"provider\": \"" + escape(provider) + "\"," + line
                    + "  \"name\": \"" + escape(name) + "\"," + line
                    + "  \"profile\": \"" + escape(profile) + "\"," + line
                    + "  \"namespace\": \"" + escape(namespace) + "\"," + line
                    + "  \"apiPort\": \"" + escape(apiPort) + "\"," + line
                    + "  \"minecraftPort\": \"" + escape(minecraftPort) + "\"," + line
                    + "  \"k3dImage\": \"" + escape(k3dImage) + "\"," + line
                    + "  \"kubeconfigFile\": \"" + escape(kubeconfigFile.toString()) + "\"," + line
                    + "  \"kindConfigFile\": \"" + escape(kindConfigFile.toString()) + "\"," + line
                    + "  \"chartDir\": \"" + escape(chartDir.toString()) + "\"" + line
                    + "}" + line;
        }

        static ClusterRunPlan fromJson(Path stateDir, String json) {
            return new ClusterRunPlan(
                    field(json, "schema"),
                    field(json, "status"),
                    stateDir,
                    field(json, "provider"),
                    field(json, "name"),
                    field(json, "profile"),
                    field(json, "namespace"),
                    field(json, "apiPort"),
                    field(json, "minecraftPort"),
                    field(json, "k3dImage"),
                    Path.of(field(json, "kubeconfigFile")),
                    Path.of(field(json, "kindConfigFile")),
                    Path.of(field(json, "chartDir")));
        }

        private static String field(String json, String name) {
            String marker = "\"" + name + "\": \"";
            int start = json.indexOf(marker);
            if (start < 0) {
                throw new IllegalArgumentException("Cluster plan missing field: " + name);
            }
            int valueStart = start + marker.length();
            int end = json.indexOf('"', valueStart);
            if (end < 0) {
                throw new IllegalArgumentException("Cluster plan has unterminated field: " + name);
            }
            return unescape(json.substring(valueStart, end));
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String unescape(String value) {
            return value.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }
}
