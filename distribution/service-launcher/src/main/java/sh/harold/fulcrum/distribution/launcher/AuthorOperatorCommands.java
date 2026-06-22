package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflight;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightReceipt;
import sh.harold.fulcrum.sdk.authoring.AuthorBundlePreflightRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffold;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffoldRequest;
import sh.harold.fulcrum.sdk.authoring.GeneratedAuthorBundle;

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
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

final class AuthorOperatorCommands {
    private static final String DEFAULT_SUBSTRATE_FINGERPRINT = "fulcrum-substrate-0.1.0";
    private static final String BUNDLE_PROPERTIES = "META-INF/fulcrum/bundle.properties";
    private static final String AUTHORING_PROPERTIES = "META-INF/fulcrum/authoring.properties";
    private static final String BUNDLE_ARTIFACT_TYPE = "application/vnd.harold.fulcrum.bundle.v1";
    private static final String BUNDLE_LAYER_TYPE = "application/vnd.harold.fulcrum.bundle.layer.v1+jar";

    private final BundleRuntimeCommandRunner commandRunner;

    AuthorOperatorCommands() {
        this(new ProcessBundleRuntimeCommandRunner());
    }

    AuthorOperatorCommands(BundleRuntimeCommandRunner commandRunner) {
        this.commandRunner = java.util.Objects.requireNonNull(commandRunner, "commandRunner");
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "new" -> create(slice(args), out);
            case "publish" -> publish(slice(args), out);
            default -> throw new IllegalArgumentException("Unknown fulcrum author command: " + args[0]);
        };
    }

    private int create(String[] args, PrintStream out) {
        OperatorArguments options = OperatorArguments.parse(args, Set.of("help"));
        options.rejectUnknown(Set.of(
                "help",
                "kind",
                "output",
                "package",
                "class",
                "substrate-fingerprint",
                "extension-point",
                "authority-domain",
                "resource-class"));
        if (options.flag("help")) {
            out.print(newUsage());
            return FulcrumLauncher.OK;
        }

        String bundleId = options.value("id")
                .or(() -> options.positionals().stream().findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Missing author bundle name"));
        AuthorKind kind = AuthorKind.from(options.value("kind").orElse("contribution"));
        String packageName = options.value("package").orElse(defaultPackage(bundleId));
        String providerSimpleName = options.value("class").orElse(defaultClassName(bundleId, kind));
        String substrateFingerprint = options.value("substrate-fingerprint").orElse(DEFAULT_SUBSTRATE_FINGERPRINT);
        Path output = Path.of(options.value("output").orElse(bundleId));
        GeneratedAuthorBundle generated = switch (kind) {
            case CONTRIBUTION -> AuthorBundleScaffold.contribution(new AuthorBundleScaffoldRequest(
                    bundleId,
                    packageName,
                    providerSimpleName,
                    contributionDescriptor(bundleId, options),
                    substrateFingerprint));
            case AUTHORITY -> AuthorBundleScaffold.authority(new AuthorBundleScaffoldRequest(
                    bundleId,
                    packageName,
                    providerSimpleName,
                    authorityDescriptor(bundleId, options),
                    substrateFingerprint));
        };
        writeGenerated(output, generated);
        out.println("authorProject=" + output.toAbsolutePath().normalize());
        out.println("bundle=" + generated.bundleId());
        out.println("kind=" + kind.id);
        out.println("provider=" + generated.providerClassName());
        out.println("substrateFingerprint=" + generated.substrateFingerprint());
        out.println("sdkCoordinates=published");
        return FulcrumLauncher.OK;
    }

    private int publish(String[] args, PrintStream out) {
        OperatorArguments options = OperatorArguments.parse(args, Set.of("help"));
        options.rejectUnknown(Set.of("help", "project", "artifact", "to"));
        if (options.flag("help")) {
            out.print(publishUsage());
            return FulcrumLauncher.OK;
        }
        Path project = Path.of(options.value("project").orElse(".")).toAbsolutePath().normalize();
        Path artifact = options.value("artifact")
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : project.resolve(path))
                .orElseGet(() -> buildArtifact(project, out))
                .toAbsolutePath()
                .normalize();
        String target = ociTarget(options.requiredValue("to"));
        PublishedAuthorBundle bundle = PublishedAuthorBundle.fromJar(artifact);
        AuthorBundlePreflightReceipt preflight = AuthorBundlePreflight.evaluate(bundle.preflightRequest());
        if (!preflight.refusals().isEmpty()) {
            out.println("bundle=" + preflight.bundleId());
            out.println("status=REFUSED");
            out.println("reason=preflight-refused");
            preflight.refusals().forEach(refusal ->
                    out.println("refusal=" + refusal.code().code() + "|" + refusal.detail()));
            return FulcrumLauncher.CONFIGURATION_BLOCKED;
        }
        BundleRuntimeCommandResult push = commandRunner.run(List.of(
                "oras",
                "push",
                "--disable-path-validation",
                "--artifact-type",
                BUNDLE_ARTIFACT_TYPE,
                target,
                artifact.toString() + ":" + BUNDLE_LAYER_TYPE), project);
        if (!push.succeeded()) {
            return publishBlocked(out, bundle, "publish-push-exited-" + push.exitCode());
        }
        BundleRuntimeCommandResult sign = commandRunner.run(List.of(
                "cosign",
                "sign",
                "--yes",
                target), project);
        if (!sign.succeeded()) {
            return publishBlocked(out, bundle, "publish-sign-exited-" + sign.exitCode());
        }
        BundleRuntimeCommandResult resolve = commandRunner.run(List.of(
                "oras",
                "resolve",
                target), project);
        if (!resolve.succeeded()) {
            return publishBlocked(out, bundle, "publish-resolve-exited-" + resolve.exitCode());
        }
        String digest = resolve.output().trim();
        if (!digest.startsWith("sha256:")) {
            return publishBlocked(out, bundle, "publish-resolve-digest-missing");
        }
        out.println("bundle=" + bundle.bundleId());
        out.println("status=PUBLISHED");
        out.println("preflight=ACCEPTED");
        out.println("artifactDigest=sha256:" + bundle.artifactDigest());
        out.println("descriptorDigest=" + bundle.descriptorDigest());
        out.println("publishedRef=oci://" + target);
        out.println("pinnedRef=oci://" + target + "@" + digest);
        out.println("signature=cosign");
        return FulcrumLauncher.OK;
    }

    private static int publishBlocked(PrintStream out, PublishedAuthorBundle bundle, String reason) {
        out.println("bundle=" + bundle.bundleId());
        out.println("status=FAILED");
        out.println("reason=" + reason);
        return FulcrumLauncher.CONFIGURATION_BLOCKED;
    }

    private static CapabilityDescriptor contributionDescriptor(String bundleId, OperatorArguments options) {
        ContributionDeclaration contribution = new ContributionDeclaration(
                extensionPoint(options.value("extension-point").orElse(CapabilityExtensionPoint.PAPER_COMMANDS.wireName())),
                CapabilityScope.NETWORK,
                0);
        return new CapabilityDescriptor(
                new CapabilityId(bundleId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(contribution),
                List.of(CapabilityScope.NETWORK));
    }

    private static CapabilityDescriptor authorityDescriptor(String bundleId, OperatorArguments options) {
        CapabilityAuthorityDeclaration authority = new CapabilityAuthorityDeclaration(
                options.value("authority-domain").orElse(defaultAuthorityDomain(bundleId)),
                options.value("resource-class").orElse(bundleId + "-backend"),
                1);
        return new CapabilityDescriptor(
                new CapabilityId(bundleId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(authority),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private static CapabilityExtensionPoint extensionPoint(String value) {
        for (CapabilityExtensionPoint point : CapabilityExtensionPoint.values()) {
            if (point.wireName().equals(value) || point.name().equals(value)) {
                return point;
            }
        }
        throw new IllegalArgumentException("Unknown extension point: " + value);
    }

    private static void writeGenerated(Path output, GeneratedAuthorBundle generated) {
        Path root = output.toAbsolutePath().normalize();
        try {
            for (var entry : generated.files().entrySet()) {
                Path file = root.resolve(entry.getKey()).normalize();
                if (!file.startsWith(root)) {
                    throw new IllegalArgumentException("generated author file escaped output directory");
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write author scaffold", exception);
        }
    }

    private static String defaultPackage(String bundleId) {
        String sanitized = bundleId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
        if (sanitized.isBlank()) {
            sanitized = "bundle";
        }
        StringBuilder packageName = new StringBuilder("external.author");
        for (String segment : sanitized.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            packageName.append('.');
            if (Character.isJavaIdentifierStart(segment.charAt(0))) {
                packageName.append(segment);
            } else {
                packageName.append('b').append(segment);
            }
        }
        return packageName.toString();
    }

    private static String defaultClassName(String bundleId, AuthorKind kind) {
        StringBuilder name = new StringBuilder();
        for (String part : bundleId.split("[^A-Za-z0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            name.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                name.append(part.substring(1));
            }
        }
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, 'B');
        }
        return name.append(kind == AuthorKind.CONTRIBUTION ? "Contribution" : "Authority").toString();
    }

    private static String defaultAuthorityDomain(String bundleId) {
        return bundleId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "")
                + ".authority";
    }

    private static Path buildArtifact(Path project, PrintStream out) {
        Path sourceRoot = project.resolve("src/main/java");
        Path resourcesRoot = project.resolve("src/main/resources");
        Path buildRoot = project.resolve("build").resolve("fulcrum-publish");
        Path classesDir = buildRoot.resolve("classes");
        String bundleId = authorProjectBundleId(project);
        Path jar = buildRoot.resolve(bundleId + ".jar");
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
                throw new RuntimeConfigurationException("fulcrum author publish requires a JDK compiler");
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
            Files.createDirectories(jar.getParent());
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                addTree(output, resourcesRoot, resourcesRoot);
                addTree(output, classesDir, classesDir);
            }
            out.println("builtArtifact=" + jar.toAbsolutePath().normalize());
            return jar;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not build author publish artifact", exception);
        }
    }

    private static String authorProjectBundleId(Path project) {
        Properties properties = properties(project.resolve("src/main/resources/META-INF/fulcrum/authoring.properties"));
        return required(properties, "bundle.id");
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

    private static String ociTarget(String value) {
        String checked = nonBlank(value, "--to");
        if (!checked.startsWith("oci://")) {
            throw new IllegalArgumentException("--to must be an oci:// registry reference");
        }
        String target = nonBlank(checked.substring("oci://".length()), "--to");
        if (target.contains("@sha256:")) {
            throw new IllegalArgumentException("--to must be a tag/ref target, not an already pinned digest");
        }
        return target;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read = input.read(buffer);
                while (read != -1) {
                    digest.update(buffer, 0, read);
                    read = input.read(buffer);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not digest author artifact", exception);
        }
    }

    private static Properties jarProperties(JarFile jar, String entryName) throws IOException {
        var entry = jar.getJarEntry(entryName);
        if (entry == null) {
            throw new IllegalArgumentException("author publish artifact missing " + entryName);
        }
        Properties properties = new Properties();
        try (var input = jar.getInputStream(entry)) {
            properties.load(input);
        }
        return properties;
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
        return Optional.ofNullable(properties.getProperty(name))
                .map(AuthorOperatorCommands::nonBlankValue)
                .orElseThrow(() -> new IllegalArgumentException("author publish metadata missing " + name));
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static CapabilityDescriptor descriptor(Properties bundle) {
        String bundleId = required(bundle, "bundle.id");
        List<ContributionDeclaration> contributions = csv(bundle.getProperty("contributions")).stream()
                .map(BundleContributionDeclarations::parse)
                .toList();
        List<CapabilityAuthorityDeclaration> authorities = csv(bundle.getProperty("authorities")).stream()
                .map(AuthorOperatorCommands::authority)
                .toList();
        return new CapabilityDescriptor(
                new CapabilityId(bundleId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                authorities,
                contributions,
                List.of(CapabilityScope.NETWORK));
    }

    private static CapabilityAuthorityDeclaration authority(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed authority declaration: " + value);
        }
        return new CapabilityAuthorityDeclaration(parts[0], parts[1], Integer.parseInt(parts[2]));
    }

    private static HostCredentialScope credentialScope(List<CapabilityAuthorityDeclaration> authorities) {
        List<HostResourceGrant> grants = new ArrayList<>();
        for (CapabilityAuthorityDeclaration authority : authorities) {
            grants.add(AuthorityBackendGrants.authorityDomain(authority.authorityDomain()));
            grants.add(AuthorityBackendGrants.resourceClass(authority.resourceClass()));
        }
        return new HostCredentialScope(Set.copyOf(grants));
    }

    private static String nonBlankValue(String value) {
        String checked = value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("author publish metadata value must not be blank");
        }
        return checked;
    }

    private static String nonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum author <new|publish> [options]",
                "",
                "Commands:",
                "  new      generate an external bundle project on published SDK coordinates",
                "  publish  build, preflight, push, sign, and pin an author bundle OCI artifact",
                "");
    }

    private static String newUsage() {
        return "Usage: fulcrum author new --kind=<contribution|authority> <name> [--output=<path>]"
                + System.lineSeparator();
    }

    private static String publishUsage() {
        return "Usage: fulcrum author publish --to=oci://<registry>/<name>:<tag> "
                + "[--project=<path>] [--artifact=<jar>] (omitting --artifact builds the project)"
                + System.lineSeparator();
    }

    private record PublishedAuthorBundle(
            Path artifact,
            String artifactDigest,
            Properties bundleProperties,
            Properties authoringProperties,
            CapabilityDescriptor descriptor) {
        private PublishedAuthorBundle {
            artifact = artifact.toAbsolutePath().normalize();
            artifactDigest = nonBlank(artifactDigest, "artifactDigest");
        }

        static PublishedAuthorBundle fromJar(Path artifact) {
            Path checked = artifact.toAbsolutePath().normalize();
            if (!Files.isRegularFile(checked)) {
                throw new IllegalArgumentException("author publish artifact does not exist: " + checked);
            }
            try (JarFile jar = new JarFile(checked.toFile())) {
                Properties bundle = jarProperties(jar, BUNDLE_PROPERTIES);
                Properties authoring = jarProperties(jar, AUTHORING_PROPERTIES);
                return new PublishedAuthorBundle(
                        checked,
                        sha256(checked),
                        bundle,
                        authoring,
                        AuthorOperatorCommands.descriptor(bundle));
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read author publish artifact", exception);
            }
        }

        String bundleId() {
            return descriptor.capabilityId().value();
        }

        String descriptorDigest() {
            return required(bundleProperties, "descriptor.digest");
        }

        AuthorBundlePreflightRequest preflightRequest() {
            return new AuthorBundlePreflightRequest(
                    descriptor,
                    descriptorDigest(),
                    artifactDigest,
                    csv(bundleProperties.getProperty("providers")),
                    descriptor.contributions(),
                    credentialScope(descriptor.authorityDomains()),
                    required(authoringProperties, "substrate.fingerprint"),
                    required(authoringProperties, "sdk.coordinate"));
        }
    }

    private enum AuthorKind {
        CONTRIBUTION("contribution"),
        AUTHORITY("authority");

        private final String id;

        AuthorKind(String id) {
            this.id = id;
        }

        private static AuthorKind from(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "contribution" -> CONTRIBUTION;
                case "authority" -> AUTHORITY;
                default -> throw new IllegalArgumentException("--kind must be contribution or authority");
            };
        }
    }
}
