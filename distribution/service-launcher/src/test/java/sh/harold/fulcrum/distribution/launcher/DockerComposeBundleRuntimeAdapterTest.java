package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DockerComposeBundleRuntimeAdapterTest {
    private static final Instant NOW = Instant.parse("2026-06-22T12:00:00Z");
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String IMAGE_DIGEST = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    private Path tempDir;

    @Test
    void startsRenderedComposeArtifactAndRecordsEvidence() {
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            commands.add(command);
            workingDirectories.add(workingDirectory);
            return new BundleRuntimeCommandResult(0, "started backend");
        };

        BundleRuntimeStartReceipt receipt = new DockerComposeBundleRuntimeAdapter(runner)
                .start(rendered(), manifest(), NOW);

        assertEquals("STARTED", receipt.status());
        assertEquals(List.of("docker", "compose", "-f", "compose.yaml", "up", "-d"), commands.get(0));
        assertEquals(tempDir, workingDirectories.get(0));
        String evidence = receipt.runtimeEvidence().orElseThrow();
        assertTrue(evidence.contains("runtime=compose"));
        assertTrue(evidence.contains("instanceId=instance-sample"));
        assertTrue(evidence.contains("manifestHash=manifest-hash"));
        assertTrue(evidence.contains("exitCode=0"));
        assertTrue(evidence.contains("outputDigest="));
    }

    @Test
    void recordsStartFailedWhenComposeCommandFails() {
        BundleRuntimeCommandRunner runner = (command, workingDirectory) ->
                new BundleRuntimeCommandResult(17, "missing network");

        BundleRuntimeStartReceipt receipt = new DockerComposeBundleRuntimeAdapter(runner)
                .start(rendered(), manifest(), NOW);

        assertEquals("START_FAILED", receipt.status());
        assertEquals("runtime-start-command-exited-17", receipt.reason());
        assertTrue(receipt.runtimeEvidence().orElseThrow().contains("exitCode=17"));
    }

    @Test
    void stopsRenderedComposeArtifactAndRecordsEvidence() {
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            commands.add(command);
            workingDirectories.add(workingDirectory);
            return new BundleRuntimeCommandResult(0, "removed backend");
        };

        BundleRuntimeStopReceipt receipt = new DockerComposeBundleRuntimeAdapter(runner)
                .stop(instanceRecord("STARTED"), NOW);

        assertEquals("STOPPED", receipt.status());
        assertEquals(List.of("docker", "compose", "-f", "compose.yaml", "down"), commands.get(0));
        assertEquals(tempDir, workingDirectories.get(0));
        String evidence = receipt.runtimeEvidence().orElseThrow();
        assertTrue(evidence.contains("runtime=compose"));
        assertTrue(evidence.contains("bundleId=sample-authority"));
        assertTrue(evidence.contains("manifestHash=manifest-hash"));
        assertTrue(evidence.contains("exitCode=0"));
    }

    @Test
    void observesRunningBackendServiceBeforeReportingRuntimeRunning() {
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            commands.add(command);
            workingDirectories.add(workingDirectory);
            return new BundleRuntimeCommandResult(0, "backend" + System.lineSeparator());
        };

        BundleRuntimeObservation observation = new DockerComposeBundleRuntimeAdapter(runner)
                .observe(instanceRecord("RUNNING"), NOW);

        assertEquals("RUNNING", observation.status());
        assertEquals(List.of(
                "docker",
                "compose",
                "-f",
                "compose.yaml",
                "ps",
                "--status",
                "running",
                "--services",
                "backend"), commands.get(0));
        assertEquals(tempDir, workingDirectories.get(0));
        assertTrue(observation.runtimeEvidence().orElseThrow().contains("outputDigest="));
    }

    @Test
    void observesStoppedWhenBackendServiceIsNotRunning() {
        BundleRuntimeCommandRunner runner = (command, workingDirectory) ->
                new BundleRuntimeCommandResult(0, "");

        BundleRuntimeObservation observation = new DockerComposeBundleRuntimeAdapter(runner)
                .observe(instanceRecord("RUNNING"), NOW);

        assertEquals("STOPPED", observation.status());
        assertEquals("runtime-process-not-running", observation.reason());
    }

    private BundleRenderedInstance rendered() {
        return new BundleRenderedInstance(
                tempDir,
                tempDir.resolve("manifest.json"),
                tempDir.resolve("env").resolve("bootstrap.env"),
                tempDir.resolve("compose.yaml"),
                tempDir.resolve("helm"),
                tempDir.resolve("helm").resolve("templates").resolve("backend-deployment.yaml"),
                tempDir.resolve("checksums.txt"),
                "manifest-hash");
    }

    private BundleInstanceRecord instanceRecord(String status) {
        return new BundleInstanceRecord(
                "sample-authority",
                status,
                DIGEST,
                Optional.of("instance-sample"),
                Optional.of("shape-sample"),
                Optional.of("manifest-hash"),
                Optional.of(tempDir.resolve("manifest.json").toString()),
                Optional.of("launch-nonce"),
                Optional.of("runtime=compose|exitCode=0"),
                Optional.empty(),
                Optional.empty(),
                Optional.of("grant-fingerprint"),
                NOW);
    }

    private static BundleInstanceManifest manifest() {
        return new BundleInstanceManifest(
                BundleInstanceManifest.SCHEMA,
                "sample-authority",
                "oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                DIGEST,
                "ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                IMAGE_DIGEST,
                "authority-backend",
                "network",
                "single-machine",
                Optional.of("full-engine"),
                List.of("sample.authority"),
                List.of("sample-backend"),
                "instance-sample",
                "authority-backend",
                "pool-network",
                "machine-single-machine",
                "principal-sample",
                "install://bundle/sample-authority/credential",
                "grant-fingerprint",
                "verified=true|sourceKind=OCI|digest=" + DIGEST,
                BundleInstanceManifest.SINGLE_MACHINE_REGISTRATION_ENDPOINT);
    }
}
