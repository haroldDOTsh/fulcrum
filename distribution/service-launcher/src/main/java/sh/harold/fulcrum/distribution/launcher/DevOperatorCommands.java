package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
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
import java.util.Arrays;
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

    private final RuntimeEnvironment environment;
    private final ClassLoader classLoader;
    private final BundleRuntimeCommandRunner commandRunner;

    DevOperatorCommands() {
        this(
                RuntimeEnvironment.system(),
                Thread.currentThread().getContextClassLoader(),
                new ProcessBundleRuntimeCommandRunner());
    }

    DevOperatorCommands(
            RuntimeEnvironment environment,
            ClassLoader classLoader,
            BundleRuntimeCommandRunner commandRunner) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        this.classLoader = java.util.Objects.requireNonNull(classLoader, "classLoader");
        this.commandRunner = java.util.Objects.requireNonNull(commandRunner, "commandRunner");
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("help"))) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        if (args.length > 0 && args[0].equals("test")) {
            return test(slice(args), out, err);
        }
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
            return refused(
                    receiptStore,
                    project,
                    "authoring.dependency.forbidden-module:" + forbidden.orElseThrow(),
                    out);
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
            return refused(receiptStore, project, preflight.refusals().getFirst().code().code(), out);
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

    private int test(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("help"))) {
            out.print(testUsage());
            return FulcrumLauncher.OK;
        }
        DevTestOptions options = DevTestOptions.parse(args);
        if (options.help()) {
            out.print(testUsage());
            return FulcrumLauncher.OK;
        }

        AuthorProject project = readProject(options.projectDir(), options.substrateFingerprint());
        AuthorDevReceiptStore receiptStore = new AuthorDevReceiptStore(options.stateDir());
        Optional<String> forbidden = forbiddenReference(options.projectDir());
        if (forbidden.isPresent()) {
            return refused(
                    receiptStore,
                    project,
                    "authoring.dependency.forbidden-module:" + forbidden.orElseThrow(),
                    out);
        }
        if (!project.kind().equals("contribution")) {
            return refused(
                    receiptStore,
                    project,
                    "authoring.dev-test.requires-contribution-probe",
                    out);
        }

        Path classesDir = compile(options.projectDir());
        Path jar = jar(options.projectDir(), classesDir, project);
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
            return refused(receiptStore, project, preflight.refusals().getFirst().code().code(), out);
        }

        ArtifactPin pin = new ArtifactPin(
                new ArtifactId("artifact.bundle." + project.bundleId()),
                artifactDigest,
                "fulcrum-bundle-v1");
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                options.stateDir().resolve("author-dev").resolve("objects"),
                BUCKET);
        putObject(objectStorage, pin, jarBytes);

        if (options.shape().equals("local-cluster")) {
            int clusterCode = ensureLocalCluster(options, out, err);
            if (clusterCode != FulcrumLauncher.OK) {
                out.println("testStatus=FAILED");
                out.println("reason=local-cluster-unavailable");
                return clusterCode;
            }
        }

        String digest = "sha256:" + artifactDigest;
        String grantDomain = "author.dev." + stableId(project.bundleId());
        String grantResource = stableId(project.bundleId()) + "-host";
        String artifactRef = "private-oci://author-dev/" + stableId(project.bundleId()) + "@" + digest;
        int installCode = new BundleOperatorCommands(environment).run(
                bundleAddArgs(options, project, artifactRef, digest, grantDomain, grantResource, jar),
                out,
                err);
        if (installCode != FulcrumLauncher.OK) {
            out.println("testStatus=FAILED");
            out.println("reason=bundle-reconcile-blocked");
            return installCode;
        }

        String probe = runContributionProbe(objectStorage, pin, project, options.stateDir());
        out.println("shape=" + options.shape());
        out.println("installPath=fulcrum-bundle-reconcile");
        out.println("probe=" + probe);
        out.println("testStatus=PASSED");
        return FulcrumLauncher.OK;
    }

    private int ensureLocalCluster(DevTestOptions options, PrintStream out, PrintStream err) {
        ClusterOperatorCommands cluster = new ClusterOperatorCommands(environment, classLoader, commandRunner);
        if (hasRunningClusterPlan(options.stateDir())) {
            int status = cluster.run(new String[]{"status", "--state-dir=" + options.stateDir()}, out, err);
            if (status == FulcrumLauncher.OK) {
                out.println("cluster=reused");
                return FulcrumLauncher.OK;
            }
        }
        return cluster.run(clusterUpArgs(options), out, err);
    }

    private static boolean hasRunningClusterPlan(Path stateDir) {
        Path planFile = stateDir.resolve("cluster-plan.json");
        if (!Files.isRegularFile(planFile)) {
            return false;
        }
        try {
            return Files.readString(planFile).contains("\"status\": \"running\"");
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read Fulcrum cluster plan", exception);
        }
    }

    private static String[] clusterUpArgs(DevTestOptions options) {
        return new String[]{
                "up",
                "--state-dir=" + options.stateDir(),
                "--provider=" + options.clusterProvider(),
                "--name=" + options.clusterName(),
                "--profile=" + options.clusterProfile(),
                "--namespace=" + options.namespace(),
                "--api-port=" + options.apiPort(),
                "--minecraft-port=" + options.minecraftPort()
        };
    }

    private static String[] bundleAddArgs(
            DevTestOptions options,
            AuthorProject project,
            String artifactRef,
            String digest,
            String grantDomain,
            String grantResource,
            Path jar) {
        List<String> args = new ArrayList<>();
        args.add("add");
        args.add(project.bundleId());
        args.add("--state-dir=" + options.stateDir());
        args.add("--kind=contribution");
        args.add("--artifact=" + artifactRef);
        args.add("--digest=" + digest);
        args.add("--scope=network");
        args.add("--placement-profile=" + placementProfile(options.shape()));
        args.add("--descriptor-digest=" + project.descriptorDigest());
        project.contributions().forEach(contribution -> args.add("--contribution=" + contributionWireValue(contribution)));
        args.add("--authority-domain=" + grantDomain);
        args.add("--resource-class=" + grantResource);
        args.add("--granted-authority-domain=" + grantDomain);
        args.add("--granted-resource-class=" + grantResource);
        args.add("--signature-evidence=author-dev-test-local-source:" + jar.toAbsolutePath().normalize());
        return args.toArray(String[]::new);
    }

    private static String placementProfile(String shape) {
        return shape.equals("local-cluster")
                ? DeploymentProfile.SMALL_PRODUCTION.id()
                : DeploymentProfile.SINGLE_MACHINE.id();
    }

    private static String contributionWireValue(ContributionDeclaration contribution) {
        return contribution.extensionPoint().wireName()
                + ":"
                + contribution.scope().value()
                + ":"
                + contribution.order();
    }

    private static String runContributionProbe(
            LocalObjectStorageAdapter objectStorage,
            ArtifactPin pin,
            AuthorProject project,
            Path stateDir) {
        ContributionBundleLoader loader = new ContributionBundleLoader(
                BUCKET,
                stateDir.resolve("author-dev").resolve("cache"),
                objectStorage::read);
        var verified = loader.verify(
                pin,
                project.descriptorDigest(),
                CapabilityMaterializationPlanner.plan(List.of(project.descriptor())));
        try (var loaded = loader.load(verified, AuthorContributionProbe.class)) {
            return loaded.provider().probe();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not close author contribution classloader", exception);
        }
    }

    private static int refused(
            AuthorDevReceiptStore receiptStore,
            AuthorProject project,
            String reason,
            PrintStream out) {
        AuthorDevReceipt receipt = AuthorDevReceipt.refused(
                project.bundleId(),
                project.kind(),
                reason,
                Clock.systemUTC().instant());
        receiptStore.append(receipt);
        printReceipt(receipt, out);
        return FulcrumLauncher.CONFIGURATION_BLOCKED;
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
        return BundleContributionDeclarations.parseAll(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList());
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
        return String.join(System.lineSeparator(),
                "Usage: fulcrum dev [--project-dir=<path>] [--state-dir=<path>] [--substrate-fingerprint=<value>]",
                "       fulcrum dev test [--shape=in-memory|local-cluster] [--project-dir=<path>] [--state-dir=<path>]",
                "",
                "Commands:",
                "  test  build, install through bundle reconcile, and smoke-check an author contribution",
                "");
    }

    private static String testUsage() {
        return "Usage: fulcrum dev test [--shape=in-memory|local-cluster] "
                + "[--project-dir=<path>] [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String stableId(String value) {
        String stable = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("^-+|-+$", "");
        return stable.isBlank() ? "bundle" : stable;
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
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

    private record DevTestOptions(
            Path projectDir,
            Path stateDir,
            Optional<String> substrateFingerprint,
            String shape,
            String clusterProvider,
            String clusterName,
            String clusterProfile,
            String namespace,
            String apiPort,
            String minecraftPort,
            boolean help) {
        static DevTestOptions parse(String[] args) {
            OperatorArguments parsed = OperatorArguments.parse(args, Set.of("help"));
            parsed.rejectUnknown(Set.of(
                    "help",
                    "project-dir",
                    "state-dir",
                    "substrate-fingerprint",
                    "shape",
                    "provider",
                    "cluster-name",
                    "cluster-profile",
                    "namespace",
                    "api-port",
                    "minecraft-port"));
            String shape = parsed.value("shape").orElse("in-memory").toLowerCase(Locale.ROOT);
            if (!shape.equals("in-memory") && !shape.equals("local-cluster")) {
                throw new IllegalArgumentException("--shape must be in-memory or local-cluster");
            }
            String clusterProvider = parsed.value("provider").orElse("k3d").toLowerCase(Locale.ROOT);
            if (!clusterProvider.equals("k3d") && !clusterProvider.equals("kind")) {
                throw new IllegalArgumentException("--provider must be k3d or kind");
            }
            String clusterProfile = parsed.value("cluster-profile").orElse(DeploymentProfile.SMALL_PRODUCTION.id());
            if (!clusterProfile.equals(DeploymentProfile.SMALL_PRODUCTION.id())
                    && !clusterProfile.equals(DeploymentProfile.LARGE_PRODUCTION.id())) {
                throw new IllegalArgumentException("--cluster-profile must be small-production or large-production");
            }
            return new DevTestOptions(
                    Path.of(parsed.value("project-dir").orElse(".")).toAbsolutePath().normalize(),
                    Path.of(parsed.value("state-dir").orElse(".fulcrum")).toAbsolutePath().normalize(),
                    parsed.value("substrate-fingerprint"),
                    shape,
                    clusterProvider,
                    parsed.value("cluster-name").orElse("fulcrum-local"),
                    clusterProfile,
                    parsed.value("namespace").orElse("fulcrum"),
                    parsed.value("api-port").orElse("16443"),
                    parsed.value("minecraft-port").orElse("25565"),
                    parsed.flag("help"));
        }
    }
}
