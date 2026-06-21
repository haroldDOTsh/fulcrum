package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffold;
import sh.harold.fulcrum.sdk.authoring.AuthorBundleScaffoldRequest;
import sh.harold.fulcrum.sdk.authoring.GeneratedAuthorBundle;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AuthorOperatorCommands {
    private static final String DEFAULT_SUBSTRATE_FINGERPRINT = "fulcrum-substrate-0.1.0";

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "new" -> create(slice(args), out);
            case "publish" -> unavailablePublish(out, err);
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

    private int unavailablePublish(PrintStream out, PrintStream err) {
        err.println("fulcrum author publish is reserved for signed OCI publication after local reload.");
        return 69;
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

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum author <new|publish> [options]",
                "",
                "Commands:",
                "  new      generate an external bundle project on published SDK coordinates",
                "  publish  reserved signed OCI publication step",
                "");
    }

    private static String newUsage() {
        return "Usage: fulcrum author new --kind=<contribution|authority> <name> [--output=<path>]"
                + System.lineSeparator();
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
