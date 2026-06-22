package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperatorAuthorCliTest {
    private static final String PROVIDER = "external.author.sample.SampleToolsContribution";

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

    @Test
    void authorPublishPreflightsPushesSignsAndPrintsPinnedOciReference() throws Exception {
        Path artifact = authorArtifact("sample-tools.jar", descriptorDigest());
        List<List<String>> commands = new ArrayList<>();
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            commands.add(command);
            if (command.size() > 1 && command.get(1).equals("resolve")) {
                return new BundleRuntimeCommandResult(
                        0,
                        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n");
            }
            return new BundleRuntimeCommandResult(0, "");
        };

        LaunchResult result = runAuthor(
                new AuthorOperatorCommands(runner),
                "publish",
                "--project=" + tempDir,
                "--artifact=" + artifact,
                "--to=oci://ghcr.io/acme/sample-tools:1.0.0");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("status=PUBLISHED"));
        assertTrue(result.out().contains("preflight=ACCEPTED"));
        assertTrue(result.out().contains("publishedRef=oci://ghcr.io/acme/sample-tools:1.0.0"));
        assertTrue(result.out().contains("pinnedRef=oci://ghcr.io/acme/sample-tools:1.0.0@sha256:cccccccc"));
        assertEquals(List.of(
                "oras",
                "push",
                "--disable-path-validation",
                "--artifact-type",
                "application/vnd.harold.fulcrum.bundle.v1",
                "ghcr.io/acme/sample-tools:1.0.0",
                artifact.toAbsolutePath().normalize() + ":application/vnd.harold.fulcrum.bundle.layer.v1+jar"), commands.get(0));
        assertEquals(List.of("cosign", "sign", "--yes", "ghcr.io/acme/sample-tools:1.0.0"), commands.get(1));
        assertEquals(List.of("oras", "resolve", "ghcr.io/acme/sample-tools:1.0.0"), commands.get(2));
    }

    @Test
    void authorPublishBuildsScaffoldedProjectWhenArtifactIsOmitted() throws Exception {
        Path project = tempDir.resolve("publish-project");
        assertEquals(FulcrumLauncher.OK, run(
                "author",
                "new",
                "--kind=contribution",
                "sample-tools",
                "--output=" + project,
                "--substrate-fingerprint=test-substrate").code());
        Path builtArtifact = project.resolve("build/fulcrum-publish/sample-tools.jar").toAbsolutePath().normalize();
        List<List<String>> commands = new ArrayList<>();
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            commands.add(List.copyOf(command));
            if (command.size() > 1 && command.get(1).equals("resolve")) {
                return new BundleRuntimeCommandResult(
                        0,
                        "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\n");
            }
            return new BundleRuntimeCommandResult(0, "");
        };

        LaunchResult result = runAuthor(
                new AuthorOperatorCommands(runner),
                "publish",
                "--project=" + project,
                "--to=oci://ghcr.io/acme/sample-tools:1.0.0");

        assertEquals(FulcrumLauncher.OK, result.code(), result.err());
        assertTrue(result.out().contains("builtArtifact=" + builtArtifact));
        assertTrue(result.out().contains("status=PUBLISHED"));
        assertTrue(Files.exists(builtArtifact));
        assertEquals(List.of(
                "oras",
                "push",
                "--disable-path-validation",
                "--artifact-type",
                "application/vnd.harold.fulcrum.bundle.v1",
                "ghcr.io/acme/sample-tools:1.0.0",
                builtArtifact + ":application/vnd.harold.fulcrum.bundle.layer.v1+jar"), commands.getFirst());
    }

    @Test
    void authorPublishRefusesBeforeRegistryMutationWhenPreflightFails() throws Exception {
        Path artifact = authorArtifact(
                "sample-tools-bad.jar",
                AuthorityBackendDescriptorDigests.sha256Hex("wrong-descriptor"));
        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            throw new AssertionError("preflight refusal must not call OCI commands");
        };

        LaunchResult result = runAuthor(
                new AuthorOperatorCommands(runner),
                "publish",
                "--project=" + tempDir,
                "--artifact=" + artifact,
                "--to=oci://ghcr.io/acme/sample-tools:1.0.0");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, result.code(), result.err());
        assertTrue(result.out().contains("status=REFUSED"));
        assertTrue(result.out().contains("reason=preflight-refused"));
        assertTrue(result.out().contains("refusal=authoring.descriptor.digest-mismatch"));
    }

    @Test
    void authorLoopRoundTripsFromScaffoldThroughDevTestPublishAndInstall() throws Exception {
        Path project = tempDir.resolve("roundtrip-project");
        Path stateDir = tempDir.resolve("roundtrip-state");
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

        LaunchResult test = run(
                "dev",
                "test",
                "--project-dir=" + project,
                "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, test.code(), test.err());
        assertTrue(test.out().contains("testStatus=PASSED"));

        BundleRuntimeCommandRunner runner = (command, workingDirectory) -> {
            if (command.size() > 1 && command.get(1).equals("resolve")) {
                return new BundleRuntimeCommandResult(
                        0,
                        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\n");
            }
            return new BundleRuntimeCommandResult(0, "");
        };
        LaunchResult publish = runAuthor(
                new AuthorOperatorCommands(runner),
                "publish",
                "--project=" + project,
                "--to=oci://ghcr.io/acme/sample-tools:1.0.0");
        assertEquals(FulcrumLauncher.OK, publish.code(), publish.err());

        LaunchResult install = run(
                "bundle",
                "add",
                "sample-tools",
                "--state-dir=" + stateDir,
                "--kind=contribution",
                "--artifact=" + lineValue(publish.out(), "pinnedRef"),
                "--digest=" + lineValue(publish.out(), "artifactDigest"),
                "--descriptor-digest=" + descriptorDigest(),
                "--contribution=Paper.Commands:network:0",
                "--authority-domain=author.dev.sample-tools",
                "--resource-class=sample-tools-host",
                "--granted-authority-domain=author.dev.sample-tools",
                "--granted-resource-class=sample-tools-host",
                "--signature-evidence=cosign:author-publish");
        assertEquals(FulcrumLauncher.OK, install.code(), install.err());
        assertTrue(install.out().contains("status=STAGED"));
    }

    private LaunchResult run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher(RuntimeEnvironment.of(Map.of(
                "FULCRUM_BUNDLE_RUNTIME_ADAPTER", "render-only"))).run(
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

    private LaunchResult runAuthor(AuthorOperatorCommands commands, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = commands.run(
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

    private Path authorArtifact(String fileName, String descriptorDigest) throws Exception {
        Path artifact = tempDir.resolve(fileName);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                    bundle.id=sample-tools
                    bundle.kind=contribution
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=%s
                    contributions=Paper.Commands:network:0
                    authorities=
                    """.formatted(descriptorDigest, PROVIDER));
            addEntry(jar, "META-INF/fulcrum/authoring.properties", """
                    substrate.fingerprint=test-substrate
                    sdk.coordinate=sh.harold.fulcrum:authoring-sdk:0.1.0
                    authority.sdk.coordinate=sh.harold.fulcrum:authority-sdk:0.1.0
                    bundle.id=sample-tools
                    bundle.kind=contribution
                    provider.class=%s
                    """.formatted(PROVIDER));
        }
        return artifact;
    }

    private static void addEntry(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static String descriptorDigest() {
        return AuthorityBackendDescriptorDigests.descriptorDigest(new CapabilityDescriptor(
                new CapabilityId("sample-tools"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ContributionDeclaration(
                        CapabilityExtensionPoint.PAPER_COMMANDS,
                        CapabilityScope.NETWORK,
                        0)),
                List.of(CapabilityScope.NETWORK)));
    }

    private static String lineValue(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring((key + "=").length()))
                .findFirst()
                .orElseThrow();
    }

    private record LaunchResult(int code, String out, String err) {
    }
}
