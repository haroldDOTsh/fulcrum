package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperatorDevCliTest {
    @TempDir
    private Path tempDir;

    @Test
    void devReloadsContributionThroughVerifiedBundleRuntime() throws Exception {
        Path project = tempDir.resolve("contribution");
        Path stateDir = tempDir.resolve("state");
        assertEquals(FulcrumLauncher.OK, run(
                "author",
                "new",
                "--kind=contribution",
                "sample-tools",
                "--output=" + project,
                "--substrate-fingerprint=test-substrate").code());

        LaunchResult dev = run(
                "dev",
                "--project-dir=" + project,
                "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, dev.code(), dev.err());
        assertTrue(dev.out().contains("status=RELOADED"));
        assertTrue(dev.out().contains("reloadMode=HOT_RELOAD"));
        assertTrue(Files.readString(stateDir.resolve(AuthorDevReceiptStore.FILE_NAME)).contains("\"status\":\"RELOADED\""));
        assertTrue(Files.exists(project.resolve("build/fulcrum-dev/sample-tools.jar")));
    }

    @Test
    void devReloadsAuthorityAsFenceUpRestartReceipt() throws Exception {
        Path project = tempDir.resolve("authority");
        Path stateDir = tempDir.resolve("state");
        assertEquals(FulcrumLauncher.OK, run(
                "author",
                "new",
                "--kind=authority",
                "sample-backend",
                "--output=" + project,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend").code());

        LaunchResult dev = run(
                "dev",
                "--project-dir=" + project,
                "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, dev.code(), dev.err());
        assertTrue(dev.out().contains("status=RELOADED"));
        assertTrue(dev.out().contains("reloadMode=FENCE_UP_RESTART"));
        assertTrue(dev.out().contains("fencingEpoch=1"));
    }

    @Test
    void devRefusesForbiddenInternalReferencesWithoutPublishingArtifact() throws Exception {
        Path project = tempDir.resolve("forbidden");
        Path stateDir = tempDir.resolve("state");
        assertEquals(FulcrumLauncher.OK, run(
                "author",
                "new",
                "--kind=contribution",
                "sample-tools",
                "--output=" + project).code());
        Path source = project.resolve("src/main/java/external/author/sample/tools/SampleToolsContribution.java");
        Files.writeString(
                source,
                Files.readString(source) + System.lineSeparator()
                        + "final class ForbiddenReference { sh.harold.fulcrum.distribution.launcher.FulcrumLauncher value; }",
                StandardCharsets.UTF_8);

        LaunchResult dev = run(
                "dev",
                "--project-dir=" + project,
                "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, dev.code(), dev.err());
        assertTrue(dev.out().contains("status=REFUSED"));
        assertTrue(dev.out().contains("authoring.dependency.forbidden-module"));
        assertFalse(Files.exists(project.resolve("build/fulcrum-dev/sample-tools.jar")));
        assertFalse(Files.exists(stateDir.resolve("author-dev").resolve("objects")));
    }

    private LaunchResult run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher(RuntimeEnvironment.of(Map.of())).run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new LaunchResult(
                code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private record LaunchResult(int code, String out, String err) {
    }
}
