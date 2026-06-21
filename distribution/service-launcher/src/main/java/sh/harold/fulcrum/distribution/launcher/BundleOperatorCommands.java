package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class BundleOperatorCommands {
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> COMMON_FLAGS = Set.of("help", "direct", "test-network", "disabled");
    private static final Set<String> COMMON_OPTIONS = Set.of(
            "state-dir",
            "profile",
            "break-glass-ticket",
            "id",
            "artifact",
            "digest",
            "kind",
            "scope",
            "placement-profile",
            "placement-tier",
            "authority-domain",
            "resource-class",
            "granted-authority-domain",
            "granted-resource-class",
            "signature-evidence");

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "add" -> add(slice(args), out);
            case "remove" -> remove(slice(args), out);
            case "list" -> list(slice(args), out);
            case "reconcile" -> reconcile(slice(args), out);
            default -> throw new IllegalArgumentException("Unknown fulcrum bundle command: " + args[0]);
        };
    }

    private int add(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(addUsage());
            return FulcrumLauncher.OK;
        }
        validateDirectMutation(options);
        String signatureEvidence = options.requiredValue("signature-evidence");
        DeclaredBundle bundle = declaredBundle(options);
        Path stateDir = stateDir(options);
        BundleDesiredStateStore desiredStateStore = new BundleDesiredStateStore(stateDir);
        BundleArtifactVerificationStore verificationStore = new BundleArtifactVerificationStore(stateDir);
        BundleReceiptStore receiptStore = new BundleReceiptStore(stateDir);

        Path desiredStateFile = desiredStateStore.write(desiredStateStore.read().addOrReplace(bundle));
        Path evidenceFile = verificationStore.recordVerified(bundle, signatureEvidence);
        directMutationAudit(options, stateDir, "add", bundle.id());
        List<BundleReconcileReceipt> receipts = newReconciler(verificationStore)
                .reconcile(desiredStateStore.read(), authorization(options));
        receiptStore.append(receipts);
        BundleReconcileReceipt receipt = receiptFor(receipts, bundle.id());
        out.println("desiredState=" + desiredStateFile);
        out.println("artifactVerification=" + evidenceFile);
        printReceipt(receipt, out);
        return receipt.status().equals("INSTALLED") ? FulcrumLauncher.OK : FulcrumLauncher.CONFIGURATION_BLOCKED;
    }

    private int remove(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(removeUsage());
            return FulcrumLauncher.OK;
        }
        validateDirectMutation(options);
        String id = bundleId(options);
        Path stateDir = stateDir(options);
        BundleDesiredStateStore desiredStateStore = new BundleDesiredStateStore(stateDir);
        BundleReceiptStore receiptStore = new BundleReceiptStore(stateDir);
        Path desiredStateFile = desiredStateStore.write(desiredStateStore.read().remove(id));
        directMutationAudit(options, stateDir, "remove", id);
        BundleReconcileReceipt receipt = new BundleReconciler(
                bundle -> Optional.empty(),
                new BundleInstallGrantIssuer(),
                Clock.systemUTC())
                .reconcileRemoval(id);
        receiptStore.append(List.of(receipt));
        out.println("desiredState=" + desiredStateFile);
        printReceipt(receipt, out);
        return FulcrumLauncher.OK;
    }

    private int list(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(listUsage());
            return FulcrumLauncher.OK;
        }
        BundleDesiredState state = new BundleDesiredStateStore(stateDir(options)).read();
        out.println("bundles=" + state.bundles().size());
        for (DeclaredBundle bundle : state.bundles()) {
            out.println("bundle=" + bundle.id()
                    + " enabled=" + bundle.enabled()
                    + " kind=" + bundle.kind()
                    + " digest=" + bundle.digest()
                    + " artifact=" + bundle.artifactRef());
        }
        return FulcrumLauncher.OK;
    }

    private int reconcile(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(reconcileUsage());
            return FulcrumLauncher.OK;
        }
        Path stateDir = stateDir(options);
        BundleArtifactVerificationStore verificationStore = new BundleArtifactVerificationStore(stateDir);
        List<BundleReconcileReceipt> receipts = newReconciler(verificationStore)
                .reconcile(new BundleDesiredStateStore(stateDir).read(), authorization(options));
        new BundleReceiptStore(stateDir).append(receipts);
        receipts.forEach(receipt -> printReceipt(receipt, out));
        return receipts.stream().anyMatch(receipt -> receipt.status().equals("DENIED"))
                ? FulcrumLauncher.CONFIGURATION_BLOCKED
                : FulcrumLauncher.OK;
    }

    private static OperatorArguments parse(String[] args) {
        OperatorArguments options = OperatorArguments.parse(args, COMMON_FLAGS);
        Set<String> allowed = new java.util.HashSet<>(COMMON_FLAGS);
        allowed.addAll(COMMON_OPTIONS);
        options.rejectUnknown(allowed);
        return options;
    }

    private static DeclaredBundle declaredBundle(OperatorArguments options) {
        String artifact = options.requiredValue("artifact");
        String digest = options.requiredValue("digest");
        validateArtifactPin(artifact, digest);
        List<String> authorityDomains = nonEmptyGrants(options.values("authority-domain"), "--authority-domain");
        List<String> resourceClasses = nonEmptyGrants(options.values("resource-class"), "--resource-class");
        return new DeclaredBundle(
                bundleId(options),
                artifact,
                digest,
                options.value("kind").orElse("authority-backend"),
                options.value("scope").orElse("network"),
                options.value("placement-profile").orElse("single-machine"),
                options.value("placement-tier"),
                authorityDomains,
                resourceClasses,
                !options.flag("disabled"));
    }

    private static BundleReconcileAuthorization authorization(OperatorArguments options) {
        return new BundleReconcileAuthorization(
                Set.copyOf(options.values("granted-authority-domain")),
                Set.copyOf(options.values("granted-resource-class")));
    }

    private static BundleReconciler newReconciler(BundleArtifactVerificationStore verificationStore) {
        return new BundleReconciler(
                verificationStore,
                new BundleInstallGrantIssuer(),
                Clock.systemUTC());
    }

    private static void validateArtifactPin(String artifact, String digest) {
        if (!SHA256_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("--digest must be sha256:<64 lowercase hex chars>");
        }
        if (!(artifact.startsWith("oci://") || artifact.startsWith("private-oci://"))) {
            throw new IllegalArgumentException("bundle install requires a digest-pinned OCI artifact reference; "
                    + "normalize tarball/local inputs before install");
        }
        if (!artifact.contains("@" + digest)) {
            throw new IllegalArgumentException("--artifact must be pinned with the same digest as --digest");
        }
    }

    private static List<String> nonEmptyGrants(List<String> values, String option) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Missing required grant request " + option);
        }
        List<String> checked = new ArrayList<>();
        values.forEach(value -> checked.add(nonBlank(value, option)));
        return List.copyOf(checked);
    }

    private static String bundleId(OperatorArguments options) {
        return options.value("id")
                .or(() -> options.positionals().stream().findFirst())
                .map(value -> nonBlank(value, "bundle id"))
                .orElseThrow(() -> new IllegalArgumentException("Missing bundle id"));
    }

    private static Path stateDir(OperatorArguments options) {
        return Path.of(options.value("state-dir").orElse(".fulcrum"));
    }

    private static void validateDirectMutation(OperatorArguments options) {
        if (!options.flag("direct")) {
            return;
        }
        String ticket = options.value("break-glass-ticket")
                .map(value -> nonBlank(value, "--break-glass-ticket"))
                .orElseThrow(() -> new IllegalArgumentException("--direct requires --break-glass-ticket"));
        String profile = options.value("profile").orElse(DeploymentProfile.SINGLE_MACHINE.id());
        boolean testNetwork = options.booleanValue("test-network", false);
        if (!profile.equals(DeploymentProfile.SINGLE_MACHINE.id()) && !testNetwork) {
            throw new IllegalArgumentException("direct bundle mutation is only available for single-machine "
                    + "or test-network break-glass operations");
        }
        if (ticket.length() < 3) {
            throw new IllegalArgumentException("--break-glass-ticket must identify the approved operation");
        }
    }

    private static void directMutationAudit(
            OperatorArguments options,
            Path stateDir,
            String action,
            String bundleId) {
        if (!options.flag("direct")) {
            return;
        }
        String line = "{"
                + "\"schema\":\"fulcrum.direct-bundle-mutation-audit/v1\","
                + "\"action\":\"" + escape(action) + "\","
                + "\"bundleId\":\"" + escape(bundleId) + "\","
                + "\"profile\":\"" + escape(options.value("profile").orElse(DeploymentProfile.SINGLE_MACHINE.id())) + "\","
                + "\"testNetwork\":" + options.booleanValue("test-network", false) + ","
                + "\"ticket\":\"" + escape(options.requiredValue("break-glass-ticket")) + "\""
                + "}" + System.lineSeparator();
        try {
            Files.createDirectories(stateDir);
            Path file = stateDir.resolve("direct-mutation-audit.jsonl");
            Files.writeString(
                    file,
                    line,
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write direct mutation audit", exception);
        }
    }

    private static BundleReconcileReceipt receiptFor(List<BundleReconcileReceipt> receipts, String bundleId) {
        return receipts.stream()
                .filter(receipt -> receipt.bundleId().equals(bundleId))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("reconcile did not emit a receipt for " + bundleId));
    }

    private static void printReceipt(BundleReconcileReceipt receipt, PrintStream out) {
        out.println("bundle=" + receipt.bundleId());
        out.println("status=" + receipt.status());
        out.println("reason=" + receipt.reason());
        out.println("digest=" + receipt.digest());
        out.println("grantFingerprint=" + receipt.grantFingerprint().orElse("none"));
        out.println("artifactEvidence=" + receipt.artifactVerificationEvidence().orElse("none"));
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum bundle <add|remove|list|reconcile> [options]",
                "",
                "Commands:",
                "  add        declare a digest-pinned bundle and reconcile it",
                "  remove     undeclare a bundle and revoke its install grant",
                "  list       print the desired bundle declarations",
                "  reconcile  reconcile the current desired state",
                "");
    }

    private static String addUsage() {
        return "Usage: fulcrum bundle add <id> --artifact=<oci-ref@sha256> --digest=<sha256> "
                + "--authority-domain=<name> --resource-class=<name> --signature-evidence=<evidence>"
                + System.lineSeparator();
    }

    private static String removeUsage() {
        return "Usage: fulcrum bundle remove <id> [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String listUsage() {
        return "Usage: fulcrum bundle list [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String reconcileUsage() {
        return "Usage: fulcrum bundle reconcile [--state-dir=<path>] "
                + "[--granted-authority-domain=<name>] [--granted-resource-class=<name>]"
                + System.lineSeparator();
    }

    private static String nonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
