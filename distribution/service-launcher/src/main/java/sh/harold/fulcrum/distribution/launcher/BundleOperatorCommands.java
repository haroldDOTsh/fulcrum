package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;
import sh.harold.fulcrum.sdk.authority.HttpAuthorityBackendRegistrationClient;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class BundleOperatorCommands {
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String RUNTIME_ADAPTER_ENV = "FULCRUM_BUNDLE_RUNTIME_ADAPTER";
    private static final String READINESS_TIMEOUT_ENV = "FULCRUM_BUNDLE_READINESS_TIMEOUT_SECONDS";
    private static final String REGISTRATION_ENDPOINT_ENV = "FULCRUM_AUTHORITY_REGISTRATION_ENDPOINT";
    private static final String DEFAULT_OPERATOR_REGISTRATION_ENDPOINT =
            "http://127.0.0.1:18085/authority-backends/register";
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
            "backend-image",
            "backend-image-digest",
            "descriptor-digest",
            "contribution",
            "authority-domain",
            "resource-class",
            "granted-authority-domain",
            "granted-resource-class",
            "signature-evidence");

    private final BundleRuntimeAdapter runtimeAdapter;
    private final BundleReadinessObserver readinessObserver;
    private final AuthorityBackendRegistrationClient registrationClient;

    BundleOperatorCommands() {
        this(RuntimeEnvironment.system());
    }

    BundleOperatorCommands(RuntimeEnvironment environment) {
        this(runtimeAdapter(environment), readinessObserver(environment), registrationClient(environment));
    }

    BundleOperatorCommands(BundleRuntimeAdapter runtimeAdapter, BundleReadinessObserver readinessObserver) {
        this(runtimeAdapter, readinessObserver, new CapabilityBackendRegistrationController());
    }

    BundleOperatorCommands(
            BundleRuntimeAdapter runtimeAdapter,
            BundleReadinessObserver readinessObserver,
            AuthorityBackendRegistrationClient registrationClient) {
        this.runtimeAdapter = java.util.Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        this.readinessObserver = java.util.Objects.requireNonNull(readinessObserver, "readinessObserver");
        this.registrationClient = java.util.Objects.requireNonNull(registrationClient, "registrationClient");
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "add" -> add(slice(args), out);
            case "remove" -> remove(slice(args), out);
            case "list" -> list(slice(args), out);
            case "status" -> status(slice(args), out);
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
        List<BundleReconcileReceipt> receipts = newReconciler(stateDir, verificationStore)
                .reconcile(desiredStateStore.read(), authorization(options));
        receiptStore.append(receipts);
        BundleReconcileReceipt receipt = receiptFor(receipts, bundle.id());
        out.println("desiredState=" + desiredStateFile);
        out.println("artifactVerification=" + evidenceFile);
        printReceipt(receipt, out);
        return receipt.satisfied() ? FulcrumLauncher.OK : FulcrumLauncher.CONFIGURATION_BLOCKED;
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
        BundleReconcileReceipt receipt = newReconciler(
                stateDir,
                new BundleArtifactVerificationStore(stateDir))
                .reconcileRemoval(id);
        receiptStore.append(List.of(receipt));
        out.println("desiredState=" + desiredStateFile);
        printReceipt(receipt, out);
        return receipt.status().equals("REMOVAL_BLOCKED")
                ? FulcrumLauncher.CONFIGURATION_BLOCKED
                : FulcrumLauncher.OK;
    }

    private int list(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(listUsage());
            return FulcrumLauncher.OK;
        }
        Path stateDir = stateDir(options);
        BundleDesiredState state = new BundleDesiredStateStore(stateDir).read();
        java.util.Map<String, BundleInstanceRecord> latestInstances =
                new BundleInstanceStateStore(stateDir).latestByBundle();
        java.util.Map<String, BundleContributionRecord> latestContributions =
                new BundleContributionStateStore(stateDir).latestByBundle();
        java.util.Map<String, BundleInstallGrantRecord> latestGrants =
                new BundleInstallGrantStateStore(stateDir).latestByBundle();
        out.println("bundles=" + state.bundles().size());
        for (DeclaredBundle bundle : state.bundles()) {
            BundleInstanceRecord instance = latestInstances.get(bundle.id());
            BundleContributionRecord contribution = latestContributions.get(bundle.id());
            BundleInstallGrantRecord grant = latestGrants.get(bundle.id());
            out.println("bundle=" + bundle.id()
                    + " enabled=" + bundle.enabled()
                    + " kind=" + bundle.kind()
                    + " digest=" + bundle.digest()
                    + " backendImage=" + bundle.backendImageRef().orElse("none")
                    + " artifact=" + bundle.artifactRef()
                    + " runtimeStatus=" + runtimeStatus(bundle, instance, contribution)
                    + " grantStatus=" + (grant == null ? "UNKNOWN" : grant.status())
                    + " instance=" + (instance == null ? "none" : instance.instanceId().orElse("none")));
        }
        return FulcrumLauncher.OK;
    }

    private int status(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print(statusUsage());
            return FulcrumLauncher.OK;
        }
        Path stateDir = stateDir(options);
        BundleDesiredState desiredState = new BundleDesiredStateStore(stateDir).read();
        java.util.Map<String, BundleInstanceRecord> latestInstances =
                new BundleInstanceStateStore(stateDir).latestByBundle();
        java.util.Map<String, BundleContributionRecord> latestContributions =
                new BundleContributionStateStore(stateDir).latestByBundle();
        java.util.Map<String, BundleInstallGrantRecord> latestGrants =
                new BundleInstallGrantStateStore(stateDir).latestByBundle();
        Optional<String> requestedId = options.value("id")
                .or(() -> options.positionals().stream().findFirst())
                .map(value -> nonBlank(value, "bundle id"));
        if (requestedId.isPresent()) {
            String id = requestedId.orElseThrow();
            Optional<DeclaredBundle> bundle = desiredState.find(id);
            BundleInstanceRecord instance = latestInstances.get(id);
            BundleContributionRecord contribution = latestContributions.get(id);
            BundleInstallGrantRecord grant = latestGrants.get(id);
            if (bundle.isEmpty() && instance == null && contribution == null && grant == null) {
                throw new IllegalArgumentException("Unknown bundle: " + id);
            }
            printStatus(bundle, instance, contribution, grant, out);
            return FulcrumLauncher.OK;
        }
        out.println("bundles=" + desiredState.bundles().size());
        for (DeclaredBundle bundle : desiredState.bundles()) {
            printStatus(
                    Optional.of(bundle),
                    latestInstances.get(bundle.id()),
                    latestContributions.get(bundle.id()),
                    latestGrants.get(bundle.id()),
                    out);
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
        List<BundleReconcileReceipt> receipts = newReconciler(stateDir, verificationStore)
                .reconcile(new BundleDesiredStateStore(stateDir).read(), authorization(options));
        new BundleReceiptStore(stateDir).append(receipts);
        receipts.forEach(receipt -> printReceipt(receipt, out));
        return receipts.stream().anyMatch(BundleOperatorCommands::blocksReconcile)
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
        String kind = options.value("kind").orElse("authority-backend");
        Optional<String> backendImage = options.value("backend-image");
        Optional<String> backendImageDigest = options.value("backend-image-digest");
        validateBackendImagePin(backendImage, backendImageDigest);
        Optional<String> descriptorDigest = options.value("descriptor-digest")
                .map(value -> validateDescriptorDigest(value, "--descriptor-digest"));
        List<sh.harold.fulcrum.capability.api.ContributionDeclaration> contributions =
                BundleContributionDeclarations.parseAll(options.values("contribution"));
        validateKindOptions(kind, backendImage, descriptorDigest, contributions);
        List<String> authorityDomains = nonEmptyGrants(options.values("authority-domain"), "--authority-domain");
        List<String> resourceClasses = nonEmptyGrants(options.values("resource-class"), "--resource-class");
        return new DeclaredBundle(
                bundleId(options),
                artifact,
                digest,
                kind,
                options.value("scope").orElse("network"),
                options.value("placement-profile").orElse("single-machine"),
                options.value("placement-tier"),
                backendImage,
                backendImageDigest,
                authorityDomains,
                resourceClasses,
                descriptorDigest,
                contributions,
                !options.flag("disabled"));
    }

    private static BundleReconcileAuthorization authorization(OperatorArguments options) {
        return new BundleReconcileAuthorization(
                Set.copyOf(options.values("granted-authority-domain")),
                Set.copyOf(options.values("granted-resource-class")));
    }

    private BundleReconciler newReconciler(
            Path stateDir,
            BundleArtifactVerificationStore verificationStore) {
        return new BundleReconciler(
                verificationStore,
                new BundleInstallGrantIssuer(),
                new BundleInstallGrantStateStore(stateDir),
                new RenderedBundleInstanceSupervisor(
                        new BundleInstanceStateStore(stateDir),
                        new BundleInstanceArtifactRenderer(stateDir),
                        runtimeAdapter,
                        readinessObserver,
                        registrationClient),
                BundleContributionRuntimeSupervisor.authorDev(stateDir),
                Clock.systemUTC());
    }

    private static BundleRuntimeAdapter runtimeAdapter(RuntimeEnvironment environment) {
        String mode = java.util.Objects.requireNonNull(environment, "environment")
                .value(RUNTIME_ADAPTER_ENV)
                .orElse("compose");
        return switch (mode) {
            case "compose" -> new DockerComposeBundleRuntimeAdapter();
            case "render-only" -> new RenderOnlyBundleRuntimeAdapter();
            default -> throw new IllegalArgumentException(
                    RUNTIME_ADAPTER_ENV + " must be compose or render-only");
        };
    }

    private static BundleReadinessObserver readinessObserver(RuntimeEnvironment environment) {
        String rawSeconds = java.util.Objects.requireNonNull(environment, "environment")
                .value(READINESS_TIMEOUT_ENV)
                .orElse("30");
        long seconds;
        try {
            seconds = Long.parseLong(rawSeconds);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(READINESS_TIMEOUT_ENV + " must be a non-negative long", exception);
        }
        if (seconds < 0) {
            throw new IllegalArgumentException(READINESS_TIMEOUT_ENV + " must be a non-negative long");
        }
        return new FileBundleReadinessObserver(Duration.ofSeconds(seconds), Duration.ofMillis(500));
    }

    private static AuthorityBackendRegistrationClient registrationClient(RuntimeEnvironment environment) {
        String endpoint = java.util.Objects.requireNonNull(environment, "environment")
                .value(REGISTRATION_ENDPOINT_ENV)
                .orElse(DEFAULT_OPERATOR_REGISTRATION_ENDPOINT);
        return new HttpAuthorityBackendRegistrationClient(URI.create(endpoint));
    }

    private static void validateArtifactPin(String artifact, String digest) {
        if (!SHA256_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("--digest must be sha256:<64 lowercase hex chars>");
        }
        if (!(artifact.startsWith("oci://") || artifact.startsWith("private-oci://"))) {
            throw new IllegalArgumentException("bundle install requires a digest-pinned OCI artifact reference; "
                    + "normalize tarball/local inputs before install");
        }
        int pinIndex = artifact.lastIndexOf("@sha256:");
        if (pinIndex < 0 || !SHA256_DIGEST.matcher(artifact.substring(pinIndex + 1)).matches()) {
            throw new IllegalArgumentException("--artifact must be pinned with a sha256 digest");
        }
        if (artifact.startsWith("private-oci://") && !artifact.contains("@" + digest)) {
            throw new IllegalArgumentException("private bundle artifacts must be pinned with the same digest as --digest");
        }
    }

    private static void validateBackendImagePin(Optional<String> image, Optional<String> digest) {
        if (image.isPresent() != digest.isPresent()) {
            throw new IllegalArgumentException("--backend-image and --backend-image-digest must be supplied together");
        }
        if (image.isEmpty()) {
            return;
        }
        String checkedImage = nonBlank(image.orElseThrow(), "--backend-image");
        String checkedDigest = nonBlank(digest.orElseThrow(), "--backend-image-digest");
        if (!SHA256_DIGEST.matcher(checkedDigest).matches()) {
            throw new IllegalArgumentException("--backend-image-digest must be sha256:<64 lowercase hex chars>");
        }
        if (!checkedImage.contains("@" + checkedDigest)) {
            throw new IllegalArgumentException("--backend-image must be pinned with --backend-image-digest");
        }
    }

    private static String validateDescriptorDigest(String value, String option) {
        String checked = nonBlank(value, option);
        if (!checked.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(option + " must be 64 lowercase hex chars");
        }
        return checked;
    }

    private static void validateKindOptions(
            String kind,
            Optional<String> backendImage,
            Optional<String> descriptorDigest,
            List<sh.harold.fulcrum.capability.api.ContributionDeclaration> contributions) {
        if (kind.equals("contribution")) {
            if (backendImage.isPresent()) {
                throw new IllegalArgumentException("contribution bundles must not declare --backend-image");
            }
            if (descriptorDigest.isEmpty()) {
                throw new IllegalArgumentException("contribution bundles require --descriptor-digest");
            }
            if (contributions.isEmpty()) {
                throw new IllegalArgumentException("contribution bundles require at least one --contribution");
            }
            return;
        }
        if (kind.equals("authority-backend")) {
            if (descriptorDigest.isPresent() || !contributions.isEmpty()) {
                throw new IllegalArgumentException("authority-backend bundles must not declare contribution metadata");
            }
            return;
        }
        throw new IllegalArgumentException("--kind must be authority-backend or contribution");
    }

    private static boolean blocksReconcile(BundleReconcileReceipt receipt) {
        return !receipt.status().equals("RUNNING")
                && !receipt.status().equals("STAGED")
                && !receipt.status().equals("REMOVED");
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
        out.println("grantState=" + receipt.grantState().orElse("none"));
        out.println("grantEvidence=" + receipt.grantEvidence().orElse("none"));
        out.println("artifactEvidence=" + receipt.artifactVerificationEvidence().orElse("none"));
        out.println("instanceId=" + receipt.instanceId().orElse("none"));
        out.println("manifestHash=" + receipt.manifestHash().orElse("none"));
        out.println("manifestPath=" + receipt.manifestPath().orElse("none"));
        out.println("launchNonce=" + receipt.launchNonce().orElse("none"));
        out.println("runtimeEvidence=" + receipt.runtimeEvidence().orElse("none"));
        out.println("registrationReceiptId=" + receipt.registrationReceiptId().orElse("none"));
        out.println("contributionCachePath=" + receipt.contributionCachePath().orElse("none"));
        out.println("contributionEvidence=" + receipt.contributionEvidence().orElse("none"));
    }

    private void printStatus(
            Optional<DeclaredBundle> bundle,
            BundleInstanceRecord instance,
            BundleContributionRecord contribution,
            BundleInstallGrantRecord grant,
            PrintStream out) {
        String bundleId = bundle.map(DeclaredBundle::id)
                .or(() -> Optional.ofNullable(instance).map(BundleInstanceRecord::bundleId))
                .or(() -> Optional.ofNullable(contribution).map(BundleContributionRecord::bundleId))
                .or(() -> Optional.ofNullable(grant).map(BundleInstallGrantRecord::bundleId))
                .orElse("none");
        out.println("bundle=" + bundleId);
        out.println("desired=" + (bundle.isPresent() ? "DECLARED" : "ABSENT"));
        out.println("enabled=" + bundle.map(DeclaredBundle::enabled).map(Object::toString).orElse("false"));
        out.println("digest=" + bundle.map(DeclaredBundle::digest)
                .or(() -> Optional.ofNullable(instance).map(BundleInstanceRecord::digest))
                .or(() -> Optional.ofNullable(contribution).map(BundleContributionRecord::digest))
                .orElse("none"));
        out.println("runtimeStatus=" + runtimeStatus(bundle.orElse(null), instance, contribution));
        out.println("grantStatus=" + (grant == null ? "UNKNOWN" : grant.status()));
        out.println("grantFingerprint=" + (grant == null ? "none" : grant.grantFingerprint()));
        out.println("instanceId=" + (instance == null ? "none" : instance.instanceId().orElse("none")));
        out.println("manifestHash=" + (instance == null ? "none" : instance.manifestHash().orElse("none")));
        out.println("manifestPath=" + (instance == null ? "none" : instance.manifestPath().orElse("none")));
        out.println("launchNonce=" + (instance == null ? "none" : instance.launchNonce().orElse("none")));
        out.println("runtimeEvidence=" + (instance == null ? "none" : instance.runtimeEvidence().orElse("none")));
        out.println("registrationReceiptId="
                + (instance == null ? "none" : instance.registrationReceiptId().orElse("none")));
        out.println("contributionCachePath="
                + (contribution == null ? "none" : contribution.cachePath().orElse("none")));
        out.println("contributionEvidence="
                + (contribution == null ? "none" : contribution.loadEvidence().orElse("none")));
    }

    private String runtimeStatus(
            DeclaredBundle bundle,
            BundleInstanceRecord instance,
            BundleContributionRecord contribution) {
        if (bundle != null && bundle.kind().equals("contribution")) {
            return contribution == null ? "UNKNOWN" : declarationAwareStatus(bundle.digest(), contribution.digest(), contribution.status());
        }
        if (bundle == null && contribution != null && instance == null) {
            return contribution.status();
        }
        if (instance == null) {
            return "UNKNOWN";
        }
        String observedStatus = observedInstanceStatus(instance);
        if (bundle == null) {
            return observedStatus;
        }
        return declarationAwareStatus(bundle.digest(), instance.digest(), observedStatus);
    }

    private String observedInstanceStatus(BundleInstanceRecord instance) {
        if (instance.status().equals("RUNNING") || instance.status().equals("STARTED")) {
            return runtimeAdapter.observe(instance, Clock.systemUTC().instant()).status();
        }
        return instance.status();
    }

    private static String declarationAwareStatus(String declaredDigest, String runtimeDigest, String status) {
        if (status.equals("REMOVED") || declaredDigest.equals(runtimeDigest)) {
            return status;
        }
        return "STALE_" + status;
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum bundle <add|remove|list|status|reconcile> [options]",
                "",
                "Commands:",
                "  add        declare a digest-pinned bundle and reconcile it",
                "  remove     undeclare a bundle and revoke its install grant",
                "  list       print the desired bundle declarations",
                "  status     print latest observed bundle instance state",
                "  reconcile  reconcile the current desired state",
                "");
    }

    private static String addUsage() {
        return "Usage: fulcrum bundle add <id> --artifact=<oci-ref@sha256> --digest=<sha256> "
                + "--backend-image=<image@sha256> --backend-image-digest=<sha256> "
                + "--authority-domain=<name> --resource-class=<name> --signature-evidence=<evidence> "
                + "[--kind=contribution --descriptor-digest=<hex> --contribution=<extensionPoint:scope:order>]"
                + System.lineSeparator();
    }

    private static String removeUsage() {
        return "Usage: fulcrum bundle remove <id> [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String listUsage() {
        return "Usage: fulcrum bundle list [--state-dir=<path>]" + System.lineSeparator();
    }

    private static String statusUsage() {
        return "Usage: fulcrum bundle status [<id>] [--state-dir=<path>]" + System.lineSeparator();
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
