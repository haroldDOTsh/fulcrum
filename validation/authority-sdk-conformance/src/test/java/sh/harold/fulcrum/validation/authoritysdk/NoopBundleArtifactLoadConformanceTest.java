package sh.harold.fulcrum.validation.authoritysdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.bundle.BundleLoadException;
import sh.harold.fulcrum.capability.bundle.BundleLoadStatus;
import sh.harold.fulcrum.capability.bundle.BundleLoadStep;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.bundle.LoadedContribution;
import sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactSourceBytes;
import sh.harold.fulcrum.core.artifact.ArtifactSourceKind;
import sh.harold.fulcrum.core.artifact.ArtifactSourcePolicy;
import sh.harold.fulcrum.core.artifact.ArtifactSourceRequest;
import sh.harold.fulcrum.core.artifact.ArtifactSourceResolver;
import sh.harold.fulcrum.core.artifact.ArtifactVerificationReceipt;
import sh.harold.fulcrum.core.artifact.ArtifactVerificationStatus;
import sh.harold.fulcrum.core.artifact.ArtifactVerificationStep;
import sh.harold.fulcrum.core.artifact.VerifiedArtifact;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NoopBundleArtifactLoadConformanceTest {
    private static final String BUCKET = "artifact-store";
    private static final String PROVIDER = "external.noop.NoopLoadedContribution";
    private static final String EXTRA_PROVIDER = "external.noop.ExtraLoadedContribution";

    @Test
    void loadsNoopContributionFromVerifiedObjectStorageArtifact(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER));
        ArtifactPin pin = publish(tempDir, jarBytes);

        ContributionBundleLoader loader = loader(tempDir, pin, jarBytes);
        VerifiedContributionBundle verified = loader.verify(
                pin,
                descriptorDigest,
                CapabilityMaterializationPlanner.plan(List.of(descriptor)));
        try (LoadedContribution<Supplier> loaded = loader.load(verified, Supplier.class)) {
            assertEquals(BundleLoadStatus.VERIFIED, verified.decision().status());
            assertEquals(List.of(
                    BundleLoadStep.PULLED,
                    BundleLoadStep.VERIFIED,
                    BundleLoadStep.MANIFEST_PARSED,
                    BundleLoadStep.MATERIALIZATION_MATCHED), verified.decision().steps());
            assertEquals(BundleLoadStatus.LOADED, loaded.decision().status());
            assertTrue(loaded.decision().steps().contains(BundleLoadStep.PROVIDER_LOADED));
            assertEquals("noop-contribution-loaded", loaded.provider().get());
            assertEquals(loaded.classLoader(), loaded.provider().getClass().getClassLoader());
            assertNotEquals(NoopBundleArtifactLoadConformanceTest.class.getClassLoader(), loaded.provider().getClass().getClassLoader());
        }
    }

    @Test
    void cacheReuseStillHashesPinnedBytes(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER));
        ArtifactPin pin = publish(tempDir, jarBytes);

        ContributionBundleLoader firstLoader = loader(tempDir, pin, jarBytes);
        firstLoader.verify(pin, descriptorDigest, CapabilityMaterializationPlanner.plan(List.of(descriptor)));

        ContributionBundleLoader cacheOnlyLoader = new ContributionBundleLoader(
                BUCKET,
                tempDir.resolve("cache"),
                address -> {
                    throw new AssertionError("cache hit must not read object storage");
                });
        VerifiedContributionBundle verified = cacheOnlyLoader.verify(
                pin,
                descriptorDigest,
                CapabilityMaterializationPlanner.plan(List.of(descriptor)));

        assertTrue(verified.decision().steps().contains(BundleLoadStep.CACHE_HIT));
        assertTrue(verified.decision().steps().contains(BundleLoadStep.VERIFIED));
    }

    @Test
    void loadsNoopContributionFromSignedVerifiedArtifactSource(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER));
        ArtifactSourceResolver resolver = new ArtifactSourceResolver(
                tempDir.resolve("cache"),
                request -> new ArtifactSourceBytes(jarBytes, request.expectedDigest(), "oci-test-source"),
                (request, digest, bytes) -> sh.harold.fulcrum.core.artifact.ArtifactSignatureReceipt.verified("cosign:issuer=fulcrum-test"));
        VerifiedArtifact artifact = resolver.resolve(new ArtifactSourceRequest(
                new ArtifactId("artifact.bundle.noop"),
                "fulcrum-bundle-v1",
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/sh-harold/noop@sha256:" + sha256(jarBytes),
                Optional.of("sha256:" + sha256(jarBytes)),
                Optional.of("cosign://ghcr.io/sh-harold/noop"),
                ArtifactSourcePolicy.production()));
        ContributionBundleLoader loader = new ContributionBundleLoader(
                BUCKET,
                tempDir.resolve("cache"),
                address -> Optional.empty());

        VerifiedContributionBundle verified = loader.verifyResolved(
                artifact,
                descriptorDigest,
                CapabilityMaterializationPlanner.plan(List.of(descriptor)));

        assertEquals(List.of(
                BundleLoadStep.SOURCE_NORMALIZED,
                BundleLoadStep.SIGNATURE_VERIFIED,
                BundleLoadStep.VERIFIED,
                BundleLoadStep.MANIFEST_PARSED,
                BundleLoadStep.MATERIALIZATION_MATCHED), verified.decision().steps());
        try (LoadedContribution<Supplier> loaded = loader.load(verified, Supplier.class)) {
            assertEquals("noop-contribution-loaded", loaded.provider().get());
        }
    }

    @Test
    void resolvedBundleLoadRefusesMissingSignatureEvidence(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER));
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.bundle.noop"), sha256(jarBytes), "fulcrum-bundle-v1");
        Path cachedPath = ArtifactBlobLayout.cachePath(tempDir.resolve("cache"), pin);
        Files.createDirectories(cachedPath.getParent());
        Files.write(cachedPath, jarBytes);
        ArtifactVerificationReceipt receipt = new ArtifactVerificationReceipt(
                ArtifactVerificationStatus.VERIFIED,
                Optional.of(pin),
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/sh-harold/noop@sha256:" + sha256(jarBytes),
                Optional.of("sha-256:" + sha256(jarBytes)),
                Optional.of(cachedPath),
                List.of(ArtifactVerificationStep.SOURCE_RESOLVED, ArtifactVerificationStep.DIGEST_PINNED),
                Optional.empty(),
                Optional.empty());
        VerifiedArtifact artifact = new VerifiedArtifact(
                pin,
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/sh-harold/noop@sha256:" + sha256(jarBytes),
                cachedPath,
                receipt);
        ContributionBundleLoader loader = new ContributionBundleLoader(
                BUCKET,
                tempDir.resolve("cache"),
                address -> Optional.empty());

        BundleLoadException exception = assertThrows(
                BundleLoadException.class,
                () -> loader.verifyResolved(
                        artifact,
                        descriptorDigest,
                        CapabilityMaterializationPlanner.plan(List.of(descriptor))));

        assertTrue(exception.getMessage().contains("signature verification missing"));
    }

    @Test
    void refusesDescriptorDigestMismatch(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER));
        ArtifactPin pin = publish(tempDir, jarBytes);

        BundleLoadException exception = assertThrows(
                BundleLoadException.class,
                () -> loader(tempDir, pin, jarBytes).verify(
                        pin,
                        AuthorityBackendDescriptorDigests.sha256Hex("wrong-descriptor"),
                        CapabilityMaterializationPlanner.plan(List.of(descriptor))));

        assertTrue(exception.getMessage().contains("descriptor digest mismatch"));
    }

    @Test
    void refusesProvidersNotDeclaredByVerifiedManifest(@TempDir Path tempDir) throws Exception {
        CapabilityDescriptor descriptor = NoopAuthorityBackend.descriptor();
        String descriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        byte[] jarBytes = providerJar(tempDir, descriptorDigest, List.of(PROVIDER), List.of(PROVIDER, EXTRA_PROVIDER));
        ArtifactPin pin = publish(tempDir, jarBytes);
        ContributionBundleLoader loader = loader(tempDir, pin, jarBytes);
        VerifiedContributionBundle verified = loader.verify(
                pin,
                descriptorDigest,
                CapabilityMaterializationPlanner.plan(List.of(descriptor)));

        BundleLoadException exception = assertThrows(
                BundleLoadException.class,
                () -> loader.load(verified, Supplier.class));

        assertTrue(exception.getMessage().contains("providers did not match"));
    }

    private static ContributionBundleLoader loader(Path tempDir, ArtifactPin pin, byte[] jarBytes) throws IOException {
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(tempDir.resolve("objects"), BUCKET);
        objectStorage.put(pin, jarBytes);
        return new ContributionBundleLoader(BUCKET, tempDir.resolve("cache"), objectStorage::read);
    }

    private static ArtifactPin publish(Path tempDir, byte[] jarBytes) throws IOException {
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.bundle.noop"), sha256(jarBytes), "fulcrum-bundle-v1");
        new LocalObjectStorageAdapter(tempDir.resolve("objects"), BUCKET).put(pin, jarBytes);
        return pin;
    }

    private static byte[] providerJar(
            Path tempDir,
            String descriptorDigest,
            List<String> manifestProviders,
            List<String> serviceProviders) throws IOException {
        Path classesDir = compileProviders(tempDir, serviceProviders);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                    bundle.id=noop-contribution-bundle
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=%s
                    contributions=Paper.Commands:network:0
                    """.formatted(descriptorDigest, String.join(",", manifestProviders)).getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/services/java.util.function.Supplier",
                    String.join("\n", serviceProviders).getBytes(StandardCharsets.UTF_8));
            try (var files = Files.walk(classesDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                    addEntry(jar, entryName, Files.readAllBytes(file));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static Path compileProviders(Path tempDir, List<String> providerClassNames) throws IOException {
        Path sourceRoot = tempDir.resolve("provider-sources");
        Path classesDir = tempDir.resolve("provider-classes");
        Files.createDirectories(classesDir);
        List<Path> sources = providerClassNames.stream()
                .map(provider -> writeProviderSource(sourceRoot, provider))
                .toList();

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for bundle provider validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sources);
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

    private static Path writeProviderSource(Path sourceRoot, String providerClassName) {
        try {
            int packageEnd = providerClassName.lastIndexOf('.');
            String packageName = providerClassName.substring(0, packageEnd);
            String simpleName = providerClassName.substring(packageEnd + 1);
            Path source = sourceRoot.resolve(providerClassName.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package %s;

                    import java.util.function.Supplier;

                    public final class %s implements Supplier<String> {
                        @Override
                        public String get() {
                            return "noop-contribution-loaded";
                        }
                    }
                    """.formatted(packageName, simpleName), StandardCharsets.UTF_8);
            return source;
        } catch (IOException exception) {
            throw new IllegalStateException("could not write provider source", exception);
        }
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
