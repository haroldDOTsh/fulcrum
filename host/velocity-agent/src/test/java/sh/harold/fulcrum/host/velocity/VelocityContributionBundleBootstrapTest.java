package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.bundle.BundleLoadStatus;
import sh.harold.fulcrum.capability.bundle.BundleLoadStep;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityContributionBundleBootstrapTest {
    private static final String BUCKET = "artifact-store";
    private static final String PROVIDER = "external.velocityprobe.VelocityHostCanaryContribution";
    private static final String DESCRIPTOR_DIGEST = sha256("velocity-host-canary-descriptor".getBytes(StandardCharsets.UTF_8));
    private static final ContributionDeclaration PROXY_COMMAND =
            new ContributionDeclaration(CapabilityExtensionPoint.PROXY_COMMANDS, CapabilityScope.NETWORK, 0);

    @Test
    void velocityHostBootstrapLoadsContributionFromVerifiedBundleClassloader(@TempDir Path tempDir)
            throws Exception {
        byte[] jarBytes = providerJar(tempDir, DESCRIPTOR_DIGEST);
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.velocity-host-canary"), sha256(jarBytes), "fulcrum-bundle-v1");
        VelocityContributionBundleBootstrap bootstrap = bootstrap(tempDir, pin, jarBytes);

        assertThrows(ClassNotFoundException.class, () ->
                Class.forName(PROVIDER, false, VelocityContributionBundleBootstrapTest.class.getClassLoader()));

        try (VelocityLoadedContribution<Supplier> contribution = bootstrap.load(declaration(pin), Supplier.class)) {
            VelocityContributionLoadReceipt receipt = contribution.receipt();

            assertEquals(BundleLoadStatus.LOADED, receipt.status());
            assertEquals("velocity-agent:unit-test", receipt.hostIdentity());
            assertEquals(Optional.of("velocity-host-canary"), receipt.bundleId());
            assertEquals(Optional.of(PROVIDER), receipt.providerClassName());
            assertTrue(receipt.providerClassLoader().orElseThrow().contains("URLClassLoader"));
            assertEquals(receipt.cachedPath().orElseThrow().toUri(), URI.create(receipt.providerCodeSource().orElseThrow()));
            assertTrue(receipt.steps().contains(BundleLoadStep.PROVIDER_LOADED));
            assertEquals("velocity-host-canary:bundle-only-marker", contribution.provider().get());
            assertEquals(contribution.loadedContribution().classLoader(), contribution.provider().getClass().getClassLoader());
            assertNotEquals(
                    VelocityContributionBundleBootstrapTest.class.getClassLoader(),
                    contribution.provider().getClass().getClassLoader());
        }
    }

    @Test
    void velocityHostBootstrapReturnsRefusalReceiptForDescriptorMismatch(@TempDir Path tempDir)
            throws Exception {
        byte[] jarBytes = providerJar(tempDir, DESCRIPTOR_DIGEST);
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.velocity-host-canary"), sha256(jarBytes), "fulcrum-bundle-v1");
        VelocityContributionBundleBootstrap bootstrap = bootstrap(tempDir, pin, jarBytes);
        VelocityContributionBundleDeclaration declaration = new VelocityContributionBundleDeclaration(
                pin,
                sha256("wrong-descriptor".getBytes(StandardCharsets.UTF_8)),
                CapabilityMaterializationPlanner.plan(List.of(descriptor())));

        VelocityContributionBundleLoadException exception = assertThrows(
                VelocityContributionBundleLoadException.class,
                () -> bootstrap.load(declaration, Supplier.class));

        VelocityContributionLoadReceipt receipt = exception.receipt();
        assertEquals(BundleLoadStatus.REFUSED, receipt.status());
        assertEquals("velocity-agent:unit-test", receipt.hostIdentity());
        assertEquals(pin, receipt.artifactPin());
        assertTrue(receipt.steps().contains(BundleLoadStep.REFUSED));
        assertTrue(receipt.refusalReason().orElseThrow().contains("descriptor digest mismatch"));
        assertFalse(receipt.providerClassName().isPresent());
    }

    private static VelocityContributionBundleBootstrap bootstrap(Path tempDir, ArtifactPin pin, byte[] jarBytes) {
        Map<String, byte[]> artifacts = Map.of(pin.artifactId().value(), jarBytes);
        return new VelocityContributionBundleBootstrap(
                "velocity-agent:unit-test",
                BUCKET,
                tempDir.resolve("cache"),
                address -> Optional.ofNullable(artifacts.get(pin.artifactId().value())));
    }

    private static VelocityContributionBundleDeclaration declaration(ArtifactPin pin) {
        return new VelocityContributionBundleDeclaration(
                pin,
                DESCRIPTOR_DIGEST,
                CapabilityMaterializationPlanner.plan(List.of(descriptor())));
    }

    private static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("velocity-host-canary"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(PROXY_COMMAND),
                List.of(CapabilityScope.NETWORK));
    }

    private static byte[] providerJar(Path tempDir, String descriptorDigest) throws IOException {
        Path classesDir = compileProvider(tempDir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                    bundle.id=velocity-host-canary
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=%s
                    contributions=Proxy.Commands:network:0
                    """.formatted(descriptorDigest, PROVIDER).getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/services/java.util.function.Supplier", PROVIDER.getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "external-velocity-canary.txt", "bundle-only-marker".getBytes(StandardCharsets.UTF_8));
            try (var files = Files.walk(classesDir)) {
                for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                    addEntry(jar, classesDir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static Path compileProvider(Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("provider-source");
        Path classesDir = tempDir.resolve("provider-classes");
        Path source = sourceRoot.resolve(PROVIDER.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);
        Files.writeString(source, """
                package external.velocityprobe;

                import java.io.IOException;
                import java.nio.charset.StandardCharsets;
                import java.util.function.Supplier;

                public final class VelocityHostCanaryContribution implements Supplier<String> {
                    @Override
                    public String get() {
                        try (var stream = getClass().getClassLoader().getResourceAsStream("external-velocity-canary.txt")) {
                            if (stream == null) {
                                throw new IllegalStateException("missing bundle marker");
                            }
                            return "velocity-host-canary:" + new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException exception) {
                            throw new IllegalStateException("could not read bundle marker", exception);
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for host bundle validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(source));
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            boolean compiled = compiler.getTask(
                    new OutputStreamWriter(errors, StandardCharsets.UTF_8),
                    fileManager,
                    null,
                    List.of("-d", classesDir.toString()),
                    null,
                    compilationUnits).call();
            assertTrue(compiled, () -> errors.toString(StandardCharsets.UTF_8));
        }
        return classesDir;
    }

    private static void addEntry(JarOutputStream jar, String entryName, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(entryName));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
