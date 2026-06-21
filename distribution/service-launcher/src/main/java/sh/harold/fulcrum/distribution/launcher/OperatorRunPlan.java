package sh.harold.fulcrum.distribution.launcher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
        String teardownCommand,
        Instant createdAt) {
    static OperatorRunPlan forSingleMachine(
            DeploymentProfile profile,
            SingleMachineTier tier,
            Path stateDir,
            Optional<java.time.Duration> runFor) {
        if (tier == SingleMachineTier.FULL_ENGINE) {
            return new OperatorRunPlan(
                    "fulcrum.operator-run-plan/v1",
                    "planned",
                    profile.id(),
                    Optional.of(tier.id()),
                    stateDir,
                    "compose",
                    "docker compose",
                    List.of(
                            "docker",
                            "compose",
                            "-f",
                            "fulcrum/compose/single-machine-full-engine.compose.yaml",
                            "--project-name",
                            "fulcrum",
                            "up",
                            "-d"),
                    "docker compose -f fulcrum/compose/single-machine-full-engine.compose.yaml --project-name fulcrum down",
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
                "fulcrum down --state-dir=" + stateDir,
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
                teardownCommand,
                createdAt);
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
                + "  \"command\": [" + commandJson() + "]," + line
                + "  \"teardownCommand\": \"" + escape(teardownCommand) + "\"," + line
                + "  \"createdAt\": \"" + createdAt + "\"" + line
                + "}" + line;
    }

    static OperatorRunPlan fromJson(Path stateDir, String json) {
        String tier = field(json, "tier");
        return new OperatorRunPlan(
                field(json, "schema"),
                field(json, "status"),
                field(json, "profile"),
                tier.equals("none") ? Optional.empty() : Optional.of(tier),
                stateDir,
                field(json, "deploymentUnit"),
                field(json, "entrypoint"),
                List.of(),
                field(json, "teardownCommand"),
                Instant.parse(field(json, "createdAt")));
    }

    private String commandJson() {
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
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        if (end < 0) {
            throw new IllegalArgumentException("Run plan has unterminated field: " + name);
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
