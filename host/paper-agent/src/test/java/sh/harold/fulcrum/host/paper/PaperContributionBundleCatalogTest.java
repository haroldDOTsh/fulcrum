package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.host.api.HostMenuContribution;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperContributionBundleCatalogTest {
    private static final String PROVIDER = "external.paperbundle.CatalogMenuContribution";
    private static final String DESCRIPTOR_DIGEST = sha256("catalog-menu-descriptor".getBytes(StandardCharsets.UTF_8));

    @Test
    void catalogLoadsDeclaredMenuContributionFromVerifiedLocalBundle(@TempDir Path tempDir) throws Exception {
        Path bundleDirectory = tempDir.resolve("contribution-bundles");
        Files.createDirectories(bundleDirectory);
        byte[] jarBytes = providerJar(tempDir);
        Path artifact = bundleDirectory.resolve("catalog-menu.jar");
        Files.write(artifact, jarBytes);
        Files.writeString(bundleDirectory.resolve("catalog-menu.properties"), """
                artifact.id=artifact.catalog-menu
                artifact.digest=%s
                artifact.compatibility=fulcrum-bundle-v1
                artifact.file=catalog-menu.jar
                descriptor.digest=%s
                contributions=Paper.Menus:network:0
                """.formatted(sha256(jarBytes), DESCRIPTOR_DIGEST), StandardCharsets.UTF_8);

        assertFalse(Files.exists(bundleDirectory.resolve(".cache")));
        assertClassNotVisibleToAmbientLoader();

        List<PaperLoadedContribution<HostMenuContribution>> loaded = new PaperContributionBundleCatalog(
                bundleDirectory,
                "paper-agent:catalog-test").loadMenuContributions();

        assertEquals(1, loaded.size());
        try (PaperLoadedContribution<HostMenuContribution> contribution = loaded.getFirst()) {
            assertEquals(Set.of("catalog"), contribution.provider().commandAliases());
            assertEquals(Optional.of(PROVIDER), contribution.receipt().providerClassName());
            assertTrue(contribution.receipt().cachedPath().orElseThrow().toString().contains(".cache"));
            assertEquals(
                    contribution.loadedContribution().classLoader(),
                    contribution.provider().getClass().getClassLoader());
            assertNotEquals(
                    PaperContributionBundleCatalogTest.class.getClassLoader(),
                    contribution.provider().getClass().getClassLoader());
        }
    }

    private static void assertClassNotVisibleToAmbientLoader() {
        try {
            Class.forName(PROVIDER, false, PaperContributionBundleCatalogTest.class.getClassLoader());
            throw new AssertionError("provider class should not be visible to the ambient classloader");
        } catch (ClassNotFoundException expected) {
            // Expected: the provider must come from the verified bundle classloader.
        }
    }

    private static byte[] providerJar(Path tempDir) throws IOException {
        Path classesDir = compileProvider(tempDir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                    bundle.id=catalog-menu
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=%s
                    contributions=Paper.Menus:network:0
                    """.formatted(DESCRIPTOR_DIGEST, PROVIDER).getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/services/sh.harold.fulcrum.host.api.HostMenuContribution",
                    PROVIDER.getBytes(StandardCharsets.UTF_8));
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
                package external.paperbundle;

                import sh.harold.fulcrum.host.api.HostMenuClickRequest;
                import sh.harold.fulcrum.host.api.HostMenuContribution;
                import sh.harold.fulcrum.host.api.HostMenuOpenRequest;
                import sh.harold.fulcrum.host.api.HostMenuRenderFrame;

                import java.util.Set;

                public final class CatalogMenuContribution implements HostMenuContribution {
                    @Override
                    public Set<String> commandAliases() {
                        return Set.of("catalog");
                    }

                    @Override
                    public HostMenuRenderFrame open(HostMenuOpenRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public HostMenuRenderFrame click(HostMenuClickRequest request) {
                        throw new UnsupportedOperationException();
                    }
                }
                """, StandardCharsets.UTF_8);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for catalog validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(source));
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            boolean compiled = compiler.getTask(
                    new OutputStreamWriter(errors, StandardCharsets.UTF_8),
                    fileManager,
                    null,
                    List.of(
                            "-classpath",
                            System.getProperty("java.class.path"),
                            "-d",
                            classesDir.toString()),
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
