package sh.harold.fulcrum.distribution.launcher;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

record OperatorRunPlan(
        String schema,
        String status,
        String profile,
        Optional<String> tier,
        Path stateDir,
        String deploymentUnit,
        String entrypoint,
        List<String> command,
        List<String> statusCommand,
        List<String> teardownCommand,
        Instant createdAt) {
    private static final String DEFAULT_COMPOSE_FILE = "fulcrum/compose/single-machine-full-engine.compose.yaml";

    OperatorRunPlan {
        schema = Objects.requireNonNull(schema, "schema");
        status = Objects.requireNonNull(status, "status");
        profile = Objects.requireNonNull(profile, "profile");
        tier = Objects.requireNonNull(tier, "tier");
        stateDir = Objects.requireNonNull(stateDir, "stateDir");
        deploymentUnit = Objects.requireNonNull(deploymentUnit, "deploymentUnit");
        entrypoint = Objects.requireNonNull(entrypoint, "entrypoint");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        statusCommand = List.copyOf(Objects.requireNonNull(statusCommand, "statusCommand"));
        teardownCommand = List.copyOf(Objects.requireNonNull(teardownCommand, "teardownCommand"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    static OperatorRunPlan forSingleMachine(
            DeploymentProfile profile,
            SingleMachineTier tier,
            Path stateDir,
            Optional<Duration> runFor) {
        return forSingleMachine(profile, tier, stateDir, runFor, Path.of(DEFAULT_COMPOSE_FILE));
    }

    static OperatorRunPlan forSingleMachine(
            DeploymentProfile profile,
            SingleMachineTier tier,
            Path stateDir,
            Optional<Duration> runFor,
            Path composeFile) {
        if (tier == SingleMachineTier.FULL_ENGINE) {
            List<String> composeBase = List.of(
                    "docker",
                    "compose",
                    "-f",
                    composeFile.toString(),
                    "--project-name",
                    "fulcrum");
            return new OperatorRunPlan(
                    "fulcrum.operator-run-plan/v1",
                    "planned",
                    profile.id(),
                    Optional.of(tier.id()),
                    stateDir,
                    "compose",
                    "docker compose",
                    append(composeBase, "up", "-d"),
                    append(composeBase, "ps"),
                    append(composeBase, "down"),
                    Instant.now());
        }

        List<String> command = new java.util.ArrayList<>(List.of(
                "fulcrum",
                "--profile=single-machine",
                "--tier=" + tier.id(),
                "--role=all",
                "--mode=run"));
        runFor.ifPresent(duration -> command.add("--run-for=" + duration));
        return new OperatorRunPlan(
                "fulcrum.operator-run-plan/v1",
                "planned",
                profile.id(),
                Optional.of(tier.id()),
                stateDir,
                "supervised-process",
                "fulcrum --role=all --mode=run",
                List.copyOf(command),
                List.of(),
                List.of("fulcrum", "down", "--state-dir=" + stateDir),
                Instant.now());
    }

    static OperatorRunPlan forHelm(
            DeploymentProfile profile,
            Path stateDir,
            String release,
            String namespace,
            Path chartDir,
            Path valuesFile) {
        List<String> helmBase = List.of("helm");
        return new OperatorRunPlan(
                "fulcrum.operator-run-plan/v1",
                "planned",
                profile.id(),
                Optional.empty(),
                stateDir,
                "helm",
                "helm",
                append(helmBase,
                        "upgrade",
                        "--install",
                        release,
                        chartDir.toString(),
                        "--namespace",
                        namespace,
                        "--create-namespace",
                        "--values",
                        valuesFile.toString(),
                        "--wait",
                        "--timeout",
                        "10m"),
                append(helmBase, "status", release, "--namespace", namespace),
                append(helmBase, "uninstall", release, "--namespace", namespace),
                Instant.now());
    }

    OperatorRunPlan withStatus(String newStatus) {
        return new OperatorRunPlan(
                schema,
                newStatus,
                profile,
                tier,
                stateDir,
                deploymentUnit,
                entrypoint,
                command,
                statusCommand,
                teardownCommand,
                createdAt);
    }

    boolean isExternallySupervised() {
        return deploymentUnit.equals("compose") || deploymentUnit.equals("helm");
    }

    String commandLine() {
        return String.join(" ", command);
    }

    String teardownCommandLine() {
        return String.join(" ", teardownCommand);
    }

    String toJson() {
        String line = System.lineSeparator();
        return "{%s".formatted(line)
                + "  \"schema\": \"" + escape(schema) + "\"," + line
                + "  \"status\": \"" + escape(status) + "\"," + line
                + "  \"profile\": \"" + escape(profile) + "\"," + line
                + "  \"tier\": \"" + escape(tier.orElse("none")) + "\"," + line
                + "  \"stateDir\": \"" + escape(stateDir.toString()) + "\"," + line
                + "  \"deploymentUnit\": \"" + escape(deploymentUnit) + "\"," + line
                + "  \"entrypoint\": \"" + escape(entrypoint) + "\"," + line
                + "  \"command\": [" + commandJson(command) + "]," + line
                + "  \"statusCommand\": [" + commandJson(statusCommand) + "]," + line
                + "  \"teardownCommand\": [" + commandJson(teardownCommand) + "]," + line
                + "  \"createdAt\": \"" + createdAt + "\"" + line
                + "}" + line;
    }

    static OperatorRunPlan fromJson(Path stateDir, String json) {
        String tier = field(json, "tier");
        List<String> teardown = optionalArrayField(json, "teardownCommand")
                .orElseGet(() -> splitLegacyCommand(field(json, "teardownCommand")));
        return new OperatorRunPlan(
                field(json, "schema"),
                field(json, "status"),
                field(json, "profile"),
                tier.equals("none") ? Optional.empty() : Optional.of(tier),
                stateDir,
                field(json, "deploymentUnit"),
                field(json, "entrypoint"),
                optionalArrayField(json, "command").orElse(List.of()),
                optionalArrayField(json, "statusCommand").orElse(List.of()),
                teardown,
                Instant.parse(field(json, "createdAt")));
    }

    private static List<String> append(List<String> command, String... values) {
        List<String> appended = new ArrayList<>(command);
        appended.addAll(List.of(values));
        return List.copyOf(appended);
    }

    private static String commandJson(List<String> command) {
        return command.stream()
                .map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\": \"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Run plan missing field: " + name);
        }
        return parseString(json, start + marker.length() - 1, name).value();
    }

    private static Optional<List<String>> optionalArrayField(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        int valueStart = skipWhitespace(json, start + marker.length());
        if (valueStart >= json.length() || json.charAt(valueStart) != '[') {
            return Optional.empty();
        }
        return Optional.of(parseArray(json, valueStart, name));
    }

    private static List<String> parseArray(String json, int arrayStart, String name) {
        List<String> values = new ArrayList<>();
        int index = skipWhitespace(json, arrayStart + 1);
        if (index < json.length() && json.charAt(index) == ']') {
            return values;
        }
        while (index < json.length()) {
            ParsedString parsed = parseString(json, index, name);
            values.add(parsed.value());
            index = skipWhitespace(json, parsed.nextIndex());
            if (index >= json.length()) {
                break;
            }
            char separator = json.charAt(index);
            if (separator == ',') {
                index = skipWhitespace(json, index + 1);
            } else if (separator == ']') {
                return List.copyOf(values);
            } else {
                throw new IllegalArgumentException("Run plan has malformed array field: " + name);
            }
        }
        throw new IllegalArgumentException("Run plan has unterminated array field: " + name);
    }

    private static ParsedString parseString(String json, int quoteIndex, String name) {
        if (quoteIndex >= json.length() || json.charAt(quoteIndex) != '"') {
            throw new IllegalArgumentException("Run plan field is not a string: " + name);
        }
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = quoteIndex + 1; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaping) {
                value.append(current);
                escaping = false;
            } else if (current == '\\') {
                escaping = true;
            } else if (current == '"') {
                return new ParsedString(value.toString(), index + 1);
            } else {
                value.append(current);
            }
        }
        throw new IllegalArgumentException("Run plan has unterminated string field: " + name);
    }

    private static int skipWhitespace(String json, int index) {
        int current = index;
        while (current < json.length() && Character.isWhitespace(json.charAt(current))) {
            current++;
        }
        return current;
    }

    private static List<String> splitLegacyCommand(String command) {
        String checked = command.trim();
        if (checked.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(checked.split("\\s+")).toList();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ParsedString(String value, int nextIndex) {
    }
}
