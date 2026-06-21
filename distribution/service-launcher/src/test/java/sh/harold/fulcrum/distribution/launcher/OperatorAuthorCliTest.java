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

final class OperatorAuthorCliTest {
    @TempDir
    private Path tempDir;

    @Test
    void authorNewCreatesExternalContributionProjectOnPublishedSdkCoordinates() throws Exception {
        Path output = tempDir.resolve("sample-tools");

        LaunchResult result = run(
                "author",
                "new",
                "--kind=contribution",
                "sample-tools",
                "--output=" + output,
                "--substrate-fingerprint=test-substrate");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("authorProject=" + output.toAbsolutePath().normalize()));
        assertTrue(result.out().contains("kind=contribution"));
        String build = Files.readString(output.resolve("build.gradle.kts"));
        assertTrue(build.contains("implementation(platform(\"sh.harold.fulcrum:fulcrum-sdk-bom:"));
        assertTrue(build.contains("api(\"sh.harold.fulcrum:authoring-sdk\")"));
        assertTrue(build.contains("api(\"sh.harold.fulcrum:authority-sdk\")"));
        assertFalse(build.contains("project("));
        assertFalse(build.contains("control:"));
        assertFalse(build.contains("distribution:"));
        assertTrue(Files.readString(output.resolve("src/main/resources/META-INF/fulcrum/authoring.properties"))
                .contains("bundle.kind=contribution"));
        assertTrue(Files.exists(output.resolve("src/test/java/external/author/sample/tools/SampleToolsContributionSmoke.java")));
    }

    @Test
    void authorNewCreatesAuthorityProjectWithAuthorityManifest() throws Exception {
        Path output = tempDir.resolve("sample-backend");

        LaunchResult result = run(
                "author",
                "new",
                "--kind=authority",
                "sample-backend",
                "--output=" + output,
                "--package=external.author.samplebackend",
                "--class=SampleBackendAuthority",
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("kind=authority"));
        assertTrue(result.out().contains("provider=external.author.samplebackend.SampleBackendAuthority"));
        assertTrue(Files.readString(output.resolve("src/main/resources/META-INF/fulcrum/authoring.properties"))
                .contains("bundle.kind=authority"));
        assertTrue(Files.readString(output.resolve("src/main/resources/META-INF/fulcrum/bundle.properties"))
                .contains("sample.authority:sample-backend:1"));
    }

    @Test
    void authorNewRejectsUnknownKindWithoutWritingProject() {
        Path output = tempDir.resolve("bad-kind");

        LaunchResult result = run(
                "author",
                "new",
                "--kind=service",
                "bad-kind",
                "--output=" + output);

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("--kind must be contribution or authority"));
        assertFalse(Files.exists(output));
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
