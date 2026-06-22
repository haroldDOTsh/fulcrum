package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class DockerComposeBundleRuntimeAdapter implements BundleRuntimeAdapter {
    private final BundleRuntimeCommandRunner commandRunner;

    DockerComposeBundleRuntimeAdapter() {
        this(new ProcessBundleRuntimeCommandRunner());
    }

    DockerComposeBundleRuntimeAdapter(BundleRuntimeCommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    @Override
    public BundleRuntimeStartReceipt start(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant now) {
        BundleRenderedInstance checkedRendered = Objects.requireNonNull(rendered, "rendered");
        BundleInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        Instant requestedAt = Objects.requireNonNull(now, "now");
        List<String> command = List.of(
                "docker",
                "compose",
                "-f",
                checkedRendered.composeFile().getFileName().toString(),
                "up",
                "-d");
        try {
            BundleRuntimeCommandResult result = commandRunner.run(command, checkedRendered.workDir());
            String evidence = evidence(checkedRendered, checkedManifest, requestedAt, command, result);
            if (result.succeeded()) {
                return BundleRuntimeStartReceipt.started(evidence);
            }
            return BundleRuntimeStartReceipt.failed("runtime-start-command-exited-" + result.exitCode(), evidence);
        } catch (RuntimeException exception) {
            return BundleRuntimeStartReceipt.failed(
                    "runtime-start-command-failed",
                    failureEvidence(checkedRendered, checkedManifest, requestedAt, command, exception));
        }
    }

    @Override
    public BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now) {
        BundleInstanceRecord checkedRecord = Objects.requireNonNull(record, "record");
        Instant requestedAt = Objects.requireNonNull(now, "now");
        if (checkedRecord.manifestPath().isEmpty()) {
            return BundleRuntimeStopReceipt.skipped("runtime-manifest-path-missing");
        }
        Path manifestFile = Path.of(checkedRecord.manifestPath().orElseThrow());
        Path workDir = manifestFile.getParent();
        if (workDir == null) {
            return BundleRuntimeStopReceipt.failed(
                    "runtime-stop-workdir-missing",
                    "runtime=compose|bundleId=" + value(checkedRecord.bundleId()) + "|exitCode=not-started");
        }
        List<String> command = List.of(
                "docker",
                "compose",
                "-f",
                "compose.yaml",
                "down");
        try {
            BundleRuntimeCommandResult result = commandRunner.run(command, workDir);
            String evidence = stopEvidence(checkedRecord, requestedAt, workDir, command, result);
            if (result.succeeded()) {
                return BundleRuntimeStopReceipt.stopped(evidence);
            }
            return BundleRuntimeStopReceipt.failed("runtime-stop-command-exited-" + result.exitCode(), evidence);
        } catch (RuntimeException exception) {
            return BundleRuntimeStopReceipt.failed(
                    "runtime-stop-command-failed",
                    stopFailureEvidence(checkedRecord, requestedAt, workDir, command, exception));
        }
    }

    @Override
    public BundleRuntimeObservation observe(BundleInstanceRecord record, Instant now) {
        BundleInstanceRecord checkedRecord = Objects.requireNonNull(record, "record");
        Instant requestedAt = Objects.requireNonNull(now, "now");
        if (!(checkedRecord.status().equals("RUNNING") || checkedRecord.status().equals("STARTED"))) {
            return BundleRuntimeObservation.fromRecord(checkedRecord);
        }
        if (checkedRecord.manifestPath().isEmpty()) {
            return BundleRuntimeObservation.failed(
                    "runtime-status-manifest-path-missing",
                    "runtime=compose|bundleId=" + value(checkedRecord.bundleId()) + "|exitCode=not-started");
        }
        Path manifestFile = Path.of(checkedRecord.manifestPath().orElseThrow());
        Path workDir = manifestFile.getParent();
        if (workDir == null) {
            return BundleRuntimeObservation.failed(
                    "runtime-status-workdir-missing",
                    "runtime=compose|bundleId=" + value(checkedRecord.bundleId()) + "|exitCode=not-started");
        }
        List<String> command = List.of(
                "docker",
                "compose",
                "-f",
                "compose.yaml",
                "ps",
                "--status",
                "running",
                "--services",
                "backend");
        try {
            BundleRuntimeCommandResult result = commandRunner.run(command, workDir);
            String evidence = statusEvidence(checkedRecord, requestedAt, workDir, command, result);
            if (!result.succeeded()) {
                return BundleRuntimeObservation.failed("runtime-status-command-exited-" + result.exitCode(), evidence);
            }
            if (serviceRunning(result.output())) {
                return BundleRuntimeObservation.processRunning(checkedRecord, evidence);
            }
            return BundleRuntimeObservation.stopped(evidence);
        } catch (RuntimeException exception) {
            return BundleRuntimeObservation.failed(
                    "runtime-status-command-failed",
                    statusFailureEvidence(checkedRecord, requestedAt, workDir, command, exception));
        }
    }

    private static String evidence(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant requestedAt,
            List<String> command,
            BundleRuntimeCommandResult result) {
        return commonEvidence(rendered, manifest, requestedAt, command)
                + "|exitCode=" + result.exitCode()
                + "|outputDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(result.output());
    }

    private static String failureEvidence(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant requestedAt,
            List<String> command,
            RuntimeException exception) {
        String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        return commonEvidence(rendered, manifest, requestedAt, command)
                + "|exitCode=not-started"
                + "|failureDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(failure);
    }

    private static String stopEvidence(
            BundleInstanceRecord record,
            Instant requestedAt,
            Path workDir,
            List<String> command,
            BundleRuntimeCommandResult result) {
        return commonStopEvidence(record, requestedAt, workDir, command)
                + "|exitCode=" + result.exitCode()
                + "|outputDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(result.output());
    }

    private static String stopFailureEvidence(
            BundleInstanceRecord record,
            Instant requestedAt,
            Path workDir,
            List<String> command,
            RuntimeException exception) {
        String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        return commonStopEvidence(record, requestedAt, workDir, command)
                + "|exitCode=not-started"
                + "|failureDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(failure);
    }

    private static String statusEvidence(
            BundleInstanceRecord record,
            Instant requestedAt,
            Path workDir,
            List<String> command,
            BundleRuntimeCommandResult result) {
        return commonStopEvidence(record, requestedAt, workDir, command)
                + "|exitCode=" + result.exitCode()
                + "|outputDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(result.output());
    }

    private static String statusFailureEvidence(
            BundleInstanceRecord record,
            Instant requestedAt,
            Path workDir,
            List<String> command,
            RuntimeException exception) {
        String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        return commonStopEvidence(record, requestedAt, workDir, command)
                + "|exitCode=not-started"
                + "|failureDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(failure);
    }

    private static boolean serviceRunning(String output) {
        return output.lines()
                .map(String::trim)
                .anyMatch("backend"::equals);
    }

    private static String commonEvidence(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant requestedAt,
            List<String> command) {
        return "runtime=compose"
                + "|instanceId=" + value(manifest.instanceId())
                + "|manifestHash=" + value(rendered.manifestHash())
                + "|workDir=" + value(rendered.workDir())
                + "|composeFile=" + value(rendered.workDir().relativize(rendered.composeFile()))
                + "|command=" + value(command.stream().collect(Collectors.joining(" ")))
                + "|requestedAt=" + requestedAt;
    }

    private static String commonStopEvidence(
            BundleInstanceRecord record,
            Instant requestedAt,
            Path workDir,
            List<String> command) {
        return "runtime=compose"
                + "|bundleId=" + value(record.bundleId())
                + "|instanceId=" + value(record.instanceId().orElse("none"))
                + "|manifestHash=" + value(record.manifestHash().orElse("none"))
                + "|workDir=" + value(workDir)
                + "|composeFile=compose.yaml"
                + "|command=" + value(command.stream().collect(Collectors.joining(" ")))
                + "|requestedAt=" + requestedAt;
    }

    private static String value(Path path) {
        return value(path.toString());
    }

    private static String value(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "/");
    }
}
