package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflight;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightReceipt;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightRequest;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightStatus;
import sh.harold.fulcrum.sdk.authoring.AuthorContributionProbe;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

final class DevOperatorCommands {
    private static final String BUCKET = "artifact-store";
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "sh.harold.fulcrum.control.",
            "sh.harold.fulcrum.data.store.",
            "sh.harold.fulcrum.distribution.");

    int run(String[] args, PrintStream out, PrintStream err) {
        OperatorArguments options = OperatorArguments.parse(args, Set.of("help"));
        options.rejectUnknown(Set.of("help", "project-dir", "state-dir", "substrate-fingerprint"));
        if (options.flag("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        Path projectDir = Path.of(options.value("project-dir").orElse(".")).toAbsolutePath().normalize();
        Path stateDir = Path.of(options.value("state-dir").orElse(".fulcrum")).toAbsolutePath().normalize();
        AuthorProject project = readProject(projectDir, options.value("substrate-fingerprint"));
        AuthorDevReceiptStore receiptStore = new AuthorDevReceiptStore(stateDir);

        Optional<String> forbidden = forbiddenReference(projectDir);
        if (forbidden.isPresent()) {
            AuthorDevReceipt receipt = AuthorDevReceipt.refused(
                    project.bundleId(),
                    project.kind(),
                    "authoring.dependency.forbidden-module:" + forbidden.orElseThrow(),
                    Clock.systemUTC().instant());
            receiptStore.append(receipt);
            printReceipt(receipt, out);
            return FulcrumLauncher.CONFIGURATION_BLOCKED;
        }

        Path classesDir = compile(projectDir);
        Path jar = jar(projectDir, classesDir, project);
        byte[] jarBytes = readAll(jar);
        String artifactDigest = sha256(jarBytes);
        AuthorBundlePreflightReceipt preflight = AuthorBundlePreflight.evaluate(new AuthorBundlePreflightRequest(
                project.descriptor(),
                project.descriptorDigest(),
                artifactDigest,
                List.of(project.providerClass()),
                project.contributions(),
                project.credentialScope(),
                project.substrateFingerprint(),
                project.sdkCoordinate()));
        if (preflight.status() == AuthorBundlePreflightStatus.REFUSED) {
            AuthorDevReceipt receipt = AuthorDevReceipt.refused(
                    project.bundleId(),
                    project.kind(),
                    preflight.refusals().getFirst().code().code(),
                    Clock.systemUTC().instant());
            receiptStore.append(receipt);
            printReceipt(receipt, out);
            return FulcrumLauncher.CONFIGURATION_BLOCKED;
        }

        ArtifactPin pin = new ArtifactPin(
                new ArtifactId("artifact.bundle." + project.bundleId()),
                artifactDigest,
                "fulcrum-bundle-v1");
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                stateDir.resolve("author-dev").resolve("objects"),
                BUCKET);
        putObject(objectStorage, pin, jarBytes);

        String reloadMode;
        long fencingEpoch = 0;
        if (project.kind().equals("contribution")) {
            ContributionBundleLoader loader = new ContributionBundleLoader(
                    BUCKET,
                    stateDir.resolve("author-dev").resolve("cache"),
                    objectStorage::read);
            var verified = loader.verify(
                    pin,
                    project.descriptorDigest(),
                    CapabilityMaterializationPlanner.plan(List.of(project.descriptor())));
            loadContribution(loader, verified);
            reloadMode = "HOT_RELOAD";
        } else {
            reloadMode = "FENCE_UP_RESTART";
            fencingEpoch = receiptStore.latest(project.bundleId())
                    .filter(receipt -> receipt.status().equals("RELOADED"))
                    .map(AuthorDevReceipt::fencingEpoch)
                    .orElse(0L) + 1;
        }

        AuthorDevReceipt receipt = AuthorDevReceipt.reloaded(
                project.bundleId(),
                project.kind(),
                artifactDigest,
                jar.toString(),
                reloadMode,
                fencingEpoch,
                Clock.systemUTC().instant());
        receiptStore.append(receipt);
        printReceipt(receipt, out);
        return FulcrumLauncher.OK;
    }

    private static void putObject(LocalObjectStorageAdapter objectStorage, ArtifactPin pin, byte[] jarBytes) {
        try {
            objectStorage.put(pin, jarBytes);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not publish author artifact by digest", exception);
        }
    }

    private static void loadContribution(
            ContributionBundleLoader loader,
            sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle verified) {
        try (var loaded = loader.load(verified, AuthorContributionProbe.class)) {
            loaded.provider().probe();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not close author contribution classloader", exception);
        }
    }

    private static AuthorProject readProject(Path projectDir, Optional<String> overrideFingerprint) {
        Properties authoring = properties(projectDir.resolve("src/main/resources/META-INF/fulcrum/authoring.properties"));
        Properties bundle = properties(projectDir.resolve("src/main/resources/META-INF/fulcrum/bundle.properties"));
        String bundleId = required(authoring, "bundle.id");
        String kind = required(authoring, "bundle.kind");
        String providerClass = required(authoring, "provider.class");
        String substrateFingerprint = overrideFingerprint.orElse(required(authoring, "substrate.fingerprint"));
        List<ContributionDeclaration> contributions = parseContributions(bundle.getProperty("contributions", ""));
        List<CapabilityAuthorityDeclaration> authorities = parseAuthorities(bundle.getProperty("authorities", ""));
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                new CapabilityId(bundleId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                authorities,
                contributions,
                List.of(CapabilityScope.NETWORK));
        return new AuthorProject(
                bundleId,
                kind,
                providerClass,
                substrateFingerprint,
                required(authoring, "sdk.coordinate"),
                required(bundle, "descriptor.digest"),
                descriptor,
                contributions,
                credentialScope(authorities));
    }

    private static Optional<String> forbiddenReference(Path projectDir) {
        try (Stream<Path> files = Files.walk(projectDir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kts"))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String forbidden : FORBIDDEN_REFERENCES) {
                    if (text.contains(forbidden)) {
                        return Optional.of(projectDir.relativize(file) + " contains " + forbidden);
                    }
                }
                if (text.contains("project(\":control")
                        || text.contains("project(\":data:store")
                        || text.contains("project(\":distribution")) {
                    return Optional.of(projectDir.relativize(file) + " contains internal project dependency");
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not scan author project", exception);
        }
    }

    private static Path compile(Path projectDir) {
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path classesDir = projectDir.resolve("build/fulcrum-dev/classes");
        try {
            Files.createDirectories(classesDir);
            List<Path> sources;
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                sources = files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("author project has no Java sources");
            }
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new RuntimeConfigurationException("fulcrum dev requires a JDK compiler");
            }
            try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
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
                        fileManager.getJavaFileObjectsFromPaths(sources)).call();
                if (!compiled) {
                    throw new RuntimeConfigurationException("author project compile failed: "
                            + errors.toString(StandardCharsets.UTF_8));
                }
            }
            return classesDir;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not compile author project", exception);
        }
    }

    private static Path jar(Path projectDir, Path classesDir, AuthorProject project) {
        Path jar = projectDir.resolve("build/fulcrum-dev/" + project.bundleId() + ".jar");
        try {
            Files.createDirectories(jar.getParent());
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                addTree(output, projectDir.resolve("src/main/resources"), projectDir.resolve("src/main/resources"));
                addTree(output, classesDir, classesDir);
            }
            return jar;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not package author bundle", exception);
        }
    }

    private static void addTree(JarOutputStream jar, Path root, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                String entryName = root.relativize(file).toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(entryName));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
    }

    private static List<ContributionDeclaration> parseContributions(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<ContributionDeclaration> contributions = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid contribution declaration: " + entry);
            }
            contributions.add(new ContributionDeclaration(
                    extensionPoint(parts[0]),
                    new CapabilityScope(parts[1]),
                    Integer.parseInt(parts[2])));
        }
        return List.copyOf(contributions);
    }

    private static List<CapabilityAuthorityDeclaration> parseAuthorities(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<CapabilityAuthorityDeclaration> authorities = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid authority declaration: " + entry);
            }
            authorities.add(new CapabilityAuthorityDeclaration(parts[0], parts[1], Integer.parseInt(parts[2])));
        }
        return List.copyOf(authorities);
    }

    private static HostCredentialScope credentialScope(List<CapabilityAuthorityDeclaration> authorities) {
        List<HostResourceGrant> grants = new ArrayList<>();
        for (CapabilityAuthorityDeclaration authority : authorities) {
            grants.add(AuthorityBackendGrants.authorityDomain(authority.authorityDomain()));
            grants.add(AuthorityBackendGrants.resourceClass(authority.resourceClass()));
        }
        return HostCredentialScope.of(grants.toArray(HostResourceGrant[]::new));
    }

    private static CapabilityExtensionPoint extensionPoint(String value) {
        for (CapabilityExtensionPoint point : CapabilityExtensionPoint.values()) {
            if (point.wireName().equals(value) || point.name().equals(value)) {
                return point;
            }
        }
        throw new IllegalArgumentException("Unknown extension point: " + value);
    }

    private static Properties properties(Path path) {
        try (var input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("author project missing " + name);
        }
        return value.trim();
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }

    private static void printReceipt(AuthorDevReceipt receipt, PrintStream out) {
        out.println("bundle=" + receipt.bundleId());
        out.println("kind=" + receipt.kind());
        out.println("status=" + receipt.status());
        out.println("reason=" + receipt.reason());
        out.println("artifactDigest=" + receipt.artifactDigest());
        out.println("reloadMode=" + receipt.reloadMode());
        out.println("fencingEpoch=" + receipt.fencingEpoch());
    }

    private static String usage() {
        return "Usage: fulcrum dev [--project-dir=<path>] [--state-dir=<path>] [--substrate-fingerprint=<value>]"
                + System.lineSeparator();
    }

    private record AuthorProject(
            String bundleId,
            String kind,
            String providerClass,
            String substrateFingerprint,
            String sdkCoordinate,
            String descriptorDigest,
            CapabilityDescriptor descriptor,
            List<ContributionDeclaration> contributions,
            HostCredentialScope credentialScope) {
    }
}
