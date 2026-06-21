package sh.harold.fulcrum.validation.authoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.bundle.BundleLoadStatus;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.bundle.LoadedContribution;
import sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflight;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightReceipt;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightRefusalCode;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightRequest;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightStatus;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffold;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffoldRequest;
import sh.harold.fulcrum.sdk.authoring.AuthorContributionProbe;
import sh.harold.fulcrum.sdk.authoring.GeneratedAuthorBundle;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthoritySdkVersion;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthoringSurfaceConformanceTest {
    private static final String BUCKET = "artifact-store";
    private static final String SUBSTRATE_FINGERPRINT = "fulcrum-substrate-0.1.0";
    private static final String SDK_COORDINATE = "sh.harold.fulcrum:authority-sdk:" + AuthoritySdkVersion.CURRENT;
    private static final ContributionDeclaration PAPER_COMMAND =
            new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 0);

    @Test
    void scaffoldedAuthorBundlePreflightsPublishesAndLoadsWithoutCoreRebuild(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = descriptor();
        GeneratedAuthorBundle scaffold = AuthorBundleScaffold.contribution(new AuthorBundleScaffoldRequest(
                "author-auction-tools",
                "external.authoring.sample",
                "SampleAuctionContribution",
                descriptor,
                SUBSTRATE_FINGERPRINT));
        Path workspace = writeExternalWorkspace(tempDir.resolve("author-workspace"), scaffold);
        Path classesDir = compileWithSdkOnlyClasspath(workspace, scaffold);
        assertNoForbiddenReferences(workspace, classesDir);

        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(scaffold, workspace, classesDir, descriptorDigest);
        String artifactDigest = sha256(jarBytes);
        AuthorBundlePreflightReceipt receipt = AuthorBundlePreflight.evaluate(preflightRequest(
                descriptor,
                descriptorDigest,
                artifactDigest,
                scaffold.providerClassName()));

        assertEquals(AuthorBundlePreflightStatus.ACCEPTED, receipt.status());
        assertEquals("author-auction-tools", receipt.bundleId());
        assertEquals(SUBSTRATE_FINGERPRINT, receipt.substrateFingerprint());
        assertTrue(receipt.refusals().isEmpty());

        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.bundle.author-auction-tools"), artifactDigest, "fulcrum-bundle-v1");
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(tempDir.resolve("objects"), BUCKET);
        objectStorage.put(pin, jarBytes);
        ContributionBundleLoader loader = new ContributionBundleLoader(BUCKET, tempDir.resolve("cache"), objectStorage::read);
        VerifiedContributionBundle verified = loader.verify(
                pin,
                descriptorDigest,
                CapabilityMaterializationPlanner.plan(List.of(descriptor)));

        try (LoadedContribution<AuthorContributionProbe> loaded = loader.load(verified, AuthorContributionProbe.class)) {
            assertEquals(BundleLoadStatus.VERIFIED, verified.decision().status());
            assertEquals(BundleLoadStatus.LOADED, loaded.decision().status());
            assertEquals("author-auction-tools loaded with SDK " + AuthoritySdkVersion.CURRENT, loaded.provider().probe());
            assertEquals(loaded.classLoader(), loaded.provider().getClass().getClassLoader());
            assertNotEquals(AuthoringSurfaceConformanceTest.class.getClassLoader(), loaded.provider().getClass().getClassLoader());
        }
    }

    @Test
    void preflightUsesStableRefusalCodesForInvalidBundles() {
        CapabilityDescriptor descriptor = descriptor();

        AuthorBundlePreflightReceipt digestMismatch = AuthorBundlePreflight.evaluate(preflightRequest(
                descriptor,
                AuthorityBackendDescriptorDigests.sha256Hex("wrong-descriptor"),
                "0".repeat(64),
                "external.authoring.sample.SampleAuctionContribution"));
        AuthorBundlePreflightReceipt forbiddenProvider = AuthorBundlePreflight.evaluate(preflightRequest(
                descriptor,
                AuthorityBackendDescriptorDigests.descriptorDigest(descriptor),
                "0".repeat(64),
                "sh.harold.fulcrum.distribution.launcher.BackdoorProvider"));

        assertEquals(AuthorBundlePreflightStatus.REFUSED, digestMismatch.status());
        assertTrue(digestMismatch.refusals().stream()
                .anyMatch(refusal -> refusal.code() == AuthorBundlePreflightRefusalCode.DESCRIPTOR_DIGEST_MISMATCH));
        assertEquals(AuthorBundlePreflightStatus.REFUSED, forbiddenProvider.status());
        assertTrue(forbiddenProvider.refusals().stream()
                .anyMatch(refusal -> refusal.code() == AuthorBundlePreflightRefusalCode.PROVIDER_SHADOWED_SUBSTRATE_CLASS));
    }

    @Test
    void authorityScaffoldUsesPublishedSdkCoordinatesAndDeclaresBackendManifest() {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                new CapabilityId("author-ledger-tools"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(new CapabilityAuthorityDeclaration("author.ledger", "author-ledger-backend", 1)),
                List.of(),
                List.of(CapabilityScope.NETWORK));

        GeneratedAuthorBundle scaffold = AuthorBundleScaffold.authority(new AuthorBundleScaffoldRequest(
                "author-ledger-tools",
                "external.authoring.ledger",
                "LedgerAuthority",
                descriptor,
                SUBSTRATE_FINGERPRINT));

        assertEquals("external.authoring.ledger.LedgerAuthority", scaffold.providerClassName());
        assertTrue(scaffold.files().get("build.gradle.kts")
                .contains("implementation(platform(\"sh.harold.fulcrum:fulcrum-sdk-bom:"));
        assertTrue(scaffold.files().get("build.gradle.kts").contains("api(\"sh.harold.fulcrum:authoring-sdk\")"));
        assertTrue(scaffold.files().get("build.gradle.kts").contains("api(\"sh.harold.fulcrum:authority-sdk\")"));
        assertTrue(scaffold.files().get("src/main/resources/META-INF/fulcrum/authoring.properties")
                .contains("bundle.kind=authority"));
        assertTrue(scaffold.files().get("src/main/resources/META-INF/fulcrum/bundle.properties")
                .contains("author.ledger:author-ledger-backend:1"));
    }

    private static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("author-auction-tools"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(PAPER_COMMAND),
                List.of(CapabilityScope.NETWORK));
    }

    private static AuthorBundlePreflightRequest preflightRequest(
            CapabilityDescriptor descriptor,
            String descriptorDigest,
            String artifactDigest,
            String providerClassName) {
        return new AuthorBundlePreflightRequest(
                descriptor,
                descriptorDigest,
                artifactDigest,
                List.of(providerClassName),
                List.of(PAPER_COMMAND),
                HostCredentialScope.of(),
                SUBSTRATE_FINGERPRINT,
                SDK_COORDINATE);
    }

    private static Path writeExternalWorkspace(Path workspace, GeneratedAuthorBundle scaffold) throws IOException {
        for (var entry : scaffold.files().entrySet()) {
            Path file = workspace.resolve(entry.getKey()).normalize();
            if (!file.startsWith(workspace)) {
                throw new IllegalArgumentException("scaffold escaped workspace");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return workspace;
    }

    private static Path compileWithSdkOnlyClasspath(Path workspace, GeneratedAuthorBundle scaffold)
            throws IOException, URISyntaxException {
        Path classesDir = workspace.resolve("build/classes");
        Files.createDirectories(classesDir);
        Path source = workspace.resolve("src/main/java/" + scaffold.providerClassName().replace('.', '/') + ".java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for author bundle validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(source));
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            boolean compiled = compiler.getTask(
                    new OutputStreamWriter(errors, StandardCharsets.UTF_8),
                    fileManager,
                    null,
                    List.of(
                            "-classpath",
                            sdkOnlyClasspath(),
                            "-d",
                            classesDir.toString()),
                    null,
                    compilationUnits).call();
            assertTrue(compiled, () -> errors.toString(StandardCharsets.UTF_8));
        }
        return classesDir;
    }

    private static String sdkOnlyClasspath() throws URISyntaxException {
        return String.join(
                File.pathSeparator,
                Path.of(AuthorContributionProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(AuthoritySdkVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }

    private static byte[] providerJar(
            GeneratedAuthorBundle scaffold,
            Path workspace,
            Path classesDir,
            String descriptorDigest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            if (!Files.exists(workspace.resolve("src/main/resources/META-INF/fulcrum/bundle.properties"))) {
                addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                        bundle.id=%s
                        descriptor.digest=%s
                        bundle.digest=declared-by-artifact-pin
                        providers=%s
                        contributions=Paper.Commands:network:0
                        """.formatted(scaffold.bundleId(), descriptorDigest, scaffold.providerClassName())
                        .getBytes(StandardCharsets.UTF_8));
            }
            addTree(jar, workspace.resolve("src/main/resources"), workspace.resolve("src/main/resources"));
            addTree(jar, classesDir, classesDir);
        }
        return bytes.toByteArray();
    }

    private static void addTree(JarOutputStream jar, Path root, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                String entryName = root.relativize(file).toString().replace('\\', '/');
                addEntry(jar, entryName, Files.readAllBytes(file));
            }
        }
    }

    private static void addEntry(JarOutputStream jar, String entryName, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(entryName));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void assertNoForbiddenReferences(Path workspace, Path classesDir) throws IOException {
        List<String> forbidden = List.of(
                "sh.harold.fulcrum.control.",
                "sh.harold.fulcrum.data.store.",
                "sh.harold.fulcrum.distribution.");
        try (var files = Files.walk(workspace)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kts"))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String term : forbidden) {
                    assertTrue(!text.contains(term), file + " contains forbidden author dependency " + term);
                }
            }
        }
        try (var files = Files.walk(classesDir)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String term : forbidden) {
                    assertTrue(!text.contains(term), file + " contains forbidden bytecode reference " + term);
                }
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
