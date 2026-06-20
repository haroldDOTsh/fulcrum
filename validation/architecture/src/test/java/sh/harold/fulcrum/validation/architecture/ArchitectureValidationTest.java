package sh.harold.fulcrum.validation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArchitectureValidationTest {
    private static final Path ROOT = findRoot();
    private static final Path ADR_ROOT = ROOT.resolve("planning/adrs");
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("project\\(\"(:[^\"]+)\"\\)");
    private static final Map<String, Pattern> SERVICE_LAUNCHER_DOMAIN_PATTERNS = Map.ofEntries(
            Map.entry("standard package import", Pattern.compile("sh\\.harold\\.fulcrum\\.standard")),
            Map.entry("standard-capabilities dependency", Pattern.compile("standard-capabilities")),
            Map.entry("standard-contracts dependency", Pattern.compile("standard-contracts")),
            Map.entry("standard topic/table prefix", Pattern.compile("standard[._]")),
            Map.entry("StandardCapability symbol", Pattern.compile("\\bStandardCapability\\b")),
            Map.entry("player profile symbol", Pattern.compile("\\b(PlayerProfile|player-profile)\\b")),
            Map.entry("punishment symbol", Pattern.compile("\\bpunishment\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("economy symbol", Pattern.compile("\\beconomy\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("stats symbol", Pattern.compile("\\bstats\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("auction symbol", Pattern.compile("\\bauction\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("guild symbol", Pattern.compile("\\bguild\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("rank symbol", Pattern.compile("\\brank\\b", Pattern.CASE_INSENSITIVE))
    );
    private static final Pattern DEFAULT_ROOT_GRAPH_STANDARD_MODULE =
            Pattern.compile("\"(?:standard-capabilities|validation:(?:standard-capabilities|fleet-e2e|synthetic-load)):");
    private static final Set<String> KERNEL_SOURCE_FILES = Set.of(
            "ArtifactId.java",
            "CapabilityId.java",
            "EffectId.java",
            "ExperienceId.java",
            "Ids.java",
            "InstanceId.java",
            "MachineRef.java",
            "PoolId.java",
            "PresenceId.java",
            "ResolvedManifestId.java",
            "RouteId.java",
            "SessionId.java",
            "SlotId.java",
            "SubjectId.java"
    );
    private static final Map<String, Set<String>> ALLOWED_PROJECT_EDGES = Map.ofEntries(
            Map.entry(":adapters:agones-allocator", Set.of(":host:host-api")),
            Map.entry(":adapters:agones-fake", Set.of(":host:host-api")),
            Map.entry(":adapters:object-storage", Set.of(":core:artifact-layout", ":core:manifest-core")),
            Map.entry(":api:contract-api", Set.of(":api:kernel-api")),
            Map.entry(":api:kernel-api", Set.of()),
            Map.entry(":capability:capability-api", Set.of(":api:contract-api", ":api:kernel-api", ":data:contract-declarations")),
            Map.entry(":capability:capability-bundle-runtime", Set.of(":capability:capability-runtime", ":core:artifact-layout")),
            Map.entry(":capability:capability-runtime", Set.of(":capability:capability-api")),
            Map.entry(":control:allocation-bridge", Set.of(":api:contract-api", ":api:kernel-api", ":control:queue-controller", ":host:host-api")),
            Map.entry(":control:capability-backend-registration", Set.of(":capability:capability-runtime", ":sdk:authority-sdk")),
            Map.entry(":control:capability-enablement-controller", Set.of(":api:contract-api", ":api:kernel-api", ":capability:capability-api")),
            Map.entry(":control:fault-controller", Set.of(":api:contract-api")),
            Map.entry(":control:instance-registry-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":control:lifecycle-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":control:queue-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":control:route-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":core:artifact-layout", Set.of(":core:manifest-core")),
            Map.entry(":core:content-resolver", Set.of(":api:contract-api", ":api:kernel-api", ":core:artifact-layout", ":core:manifest-core")),
            Map.entry(":core:manifest-core", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":core:session-runtime", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":data:artifact-authority", Set.of(":api:contract-api", ":data:authority-core")),
            Map.entry(":data:authority-core", Set.of(":api:contract-api")),
            Map.entry(":data:authority-runtime", Set.of(":api:contract-api", ":data:authority-core")),
            Map.entry(":data:contract-codegen", Set.of(":api:contract-api", ":data:contract-declarations")),
            Map.entry(":data:contract-declarations", Set.of(":api:contract-api")),
            Map.entry(":data:presence-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":data:route-contract", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":data:route-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core", ":data:route-contract")),
            Map.entry(":data:session-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":data:store-cassandra", Set.of(":data:authority-runtime")),
            Map.entry(":data:store-kafka", Set.of(":data:authority-runtime")),
            Map.entry(":data:store-postgresql", Set.of(":data:authority-runtime")),
            Map.entry(":data:store-valkey", Set.of(":data:authority-runtime")),
            Map.entry(":data:subject-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":distribution:profiles", Set.of()),
            Map.entry(":distribution:service-launcher", Set.of(":adapters:agones-allocator", ":adapters:agones-fake", ":adapters:object-storage", ":api:contract-api", ":api:kernel-api", ":capability:capability-runtime", ":control:allocation-bridge", ":control:capability-backend-registration", ":control:capability-enablement-controller", ":control:fault-controller", ":control:instance-registry-controller", ":control:lifecycle-controller", ":control:queue-controller", ":control:route-controller", ":data:artifact-authority", ":data:authority-runtime", ":data:presence-authority", ":data:route-authority", ":data:session-authority", ":data:store-cassandra", ":data:store-kafka", ":data:store-postgresql", ":data:store-valkey", ":data:subject-authority", ":distribution:profiles", ":host:effect-admission", ":host:host-api", ":host:paper-agent", ":host:tick-runtime-api", ":host:velocity-agent", ":host:worker-agent", ":testkit:substrate-testkit")),
            Map.entry(":host:effect-admission", Set.of(":core:session-runtime", ":host:host-api")),
            Map.entry(":host:host-api", Set.of(":api:contract-api", ":api:kernel-api", ":core:manifest-core")),
            Map.entry(":host:paper-agent", Set.of(":capability:capability-bundle-runtime", ":core:artifact-layout", ":host:host-api", ":host:tick-runtime-api")),
            Map.entry(":host:tick-runtime-api", Set.of(":core:session-runtime", ":host:host-api")),
            Map.entry(":host:velocity-agent", Set.of(":capability:capability-bundle-runtime", ":host:host-api", ":data:route-contract")),
            Map.entry(":host:worker-agent", Set.of(":api:contract-api", ":api:kernel-api", ":host:host-api")),
            Map.entry(":platform:fulcrum-bom", Set.of(":sdk:authoring-sdk", ":sdk:authority-sdk")),
            Map.entry(":sdk:authoring-sdk", Set.of(":capability:capability-runtime", ":sdk:authority-sdk")),
            Map.entry(":sdk:authority-sdk", Set.of(":api:contract-api", ":api:kernel-api", ":capability:capability-api", ":data:authority-runtime", ":host:host-api")),
            Map.entry(":testkit:architecture-testkit", Set.of()),
            Map.entry(":testkit:substrate-testkit", Set.of(":capability:capability-runtime", ":data:artifact-authority", ":data:contract-codegen", ":data:presence-authority")),
            Map.entry(":validation:architecture", Set.of()),
            Map.entry(":validation:auction-escrow-contract", Set.of(":api:contract-api", ":api:kernel-api", ":capability:capability-api", ":data:contract-declarations")),
            Map.entry(":validation:auction-escrow-backend", Set.of(":control:capability-backend-registration", ":data:store-cassandra", ":data:store-kafka", ":data:store-postgresql", ":data:store-valkey", ":sdk:authority-sdk", ":testkit:substrate-testkit", ":validation:auction-escrow-contract", ":validation:auction-experience-bundle")),
            Map.entry(":validation:auction-experience-bundle", Set.of(":host:host-api", ":host:paper-agent", ":sdk:authority-sdk", ":validation:auction-escrow-contract")),
            Map.entry(":validation:authoring-sdk-conformance", Set.of(":adapters:object-storage", ":capability:capability-bundle-runtime", ":capability:capability-runtime", ":core:manifest-core", ":sdk:authoring-sdk", ":sdk:authority-sdk")),
            Map.entry(":validation:authority-sdk-conformance", Set.of(":adapters:object-storage", ":capability:capability-bundle-runtime", ":control:capability-backend-registration", ":sdk:authority-sdk")),
            Map.entry(":validation:escrow-e2e", Set.of(":control:capability-backend-registration", ":sdk:authority-sdk", ":validation:auction-escrow-backend", ":validation:auction-escrow-contract", ":validation:auction-experience-bundle")),
            Map.entry(":validation:store-adapter-certification", Set.of(":adapters:object-storage", ":api:contract-api", ":api:kernel-api", ":core:artifact-layout", ":core:manifest-core", ":data:authority-core", ":data:authority-runtime", ":data:store-cassandra", ":data:store-kafka", ":data:store-postgresql", ":data:store-valkey", ":testkit:substrate-testkit"))
    );

    @Test
    void productionPackagesUseFulcrumNamespace() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (!text.contains("package sh.harold.fulcrum.")) {
                violations.add(ROOT.relativize(source).toString());
            }
        }
        assertTrue(violations.isEmpty(), () -> "Production sources outside sh.harold.fulcrum: " + violations);
    }

    @Test
    void kernelApiContainsOnlyMicrokernelNames() throws IOException {
        Path kernelSource = ROOT.resolve("api/kernel-api/src/main/java/sh/harold/fulcrum/api/kernel");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(kernelSource)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> !KERNEL_SOURCE_FILES.contains(fileName))
                    .forEach(violations::add);
        }
        assertTrue(violations.isEmpty(), () -> "Unexpected kernel API source files: " + violations);
    }

    @Test
    void moduleDependenciesFollowAllowedDirection() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path buildFile : buildFiles()) {
            String module = modulePathFor(buildFile);
            if (module == null) {
                continue;
            }
            Set<String> allowed = ALLOWED_PROJECT_EDGES.getOrDefault(module, Set.of());
            String text = Files.readString(buildFile, StandardCharsets.UTF_8);
            var matcher = PROJECT_DEPENDENCY.matcher(text);
            while (matcher.find()) {
                String dependency = matcher.group(1);
                if (!allowed.contains(dependency)) {
                    violations.add(module + " -> " + dependency);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Illegal project dependency edges: " + violations);
    }

    @Test
    void defaultRootBuildDoesNotIncludeLegacyStandardModules() throws IOException {
        String settings = Files.readString(ROOT.resolve("settings.gradle.kts"), StandardCharsets.UTF_8);
        String rootBuild = Files.readString(ROOT.resolve("build.gradle.kts"), StandardCharsets.UTF_8);
        assertFalse(
                DEFAULT_ROOT_GRAPH_STANDARD_MODULE.matcher(settings).find(),
                "default settings.gradle.kts must not include legacy standard capability projects");
        assertFalse(
                rootBuild.contains(":standard-capabilities:"),
                "root lifecycle checks must not depend on legacy standard capability projects");
        assertFalse(
                rootBuild.contains(":validation:standard-capabilities"),
                "root lifecycle checks must not depend on legacy standard capability validation");
        assertFalse(
                rootBuild.contains(":validation:fleet-e2e"),
                "root lifecycle checks must not depend on legacy fleet E2E validation");
        assertFalse(
                rootBuild.contains(":validation:synthetic-load"),
                "root lifecycle checks must not depend on legacy synthetic-load validation");
    }

    @Test
    void serviceLauncherProductionSurfaceStaysDomainBlind() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : serviceLauncherDomainBlindTextFiles()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            SERVICE_LAUNCHER_DOMAIN_PATTERNS.forEach((label, pattern) -> {
                if (pattern.matcher(text).find()) {
                    violations.add(ROOT.relativize(file).toString() + " contains " + label);
                }
            });
        }
        assertTrue(violations.isEmpty(), () -> "Service launcher domain coupling found: " + violations);
    }

    @Test
    void auctionExperienceBundleCannotImportEscrowBackend() throws IOException {
        Path experience = ROOT.resolve("validation/auction-experience-bundle");
        if (!Files.exists(experience)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        String buildFile = Files.readString(experience.resolve("build.gradle.kts"), StandardCharsets.UTF_8);
        if (buildFile.contains(":validation:auction-escrow-backend")) {
            violations.add("auction experience build file depends on escrow backend");
        }

        List<String> forbidden = List.of(
                "AuctionEscrowAuthority",
                "AuctionEscrowState",
                "AuctionEscrowReceipt",
                "EscrowSnapshot",
                "ReleasePlan",
                "ReleaseLine"
        );
        try (Stream<Path> files = Files.walk(experience.resolve("src/main/java"))) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains backend symbol " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Auction experience crossed escrow backend boundary: " + violations);
    }

    @Test
    void coreAndHostBuildFilesDoNotUseForbiddenRuntimeClients() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of("paper-api", "velocity-api", "kafka", "cassandra", "postgresql", "jdbc", "valkey", "agones");
        for (Path buildFile : buildFiles()) {
            String normalized = ROOT.relativize(buildFile).toString().replace('\\', '/');
            if (!normalized.startsWith("core/") && !normalized.startsWith("host/")) {
                continue;
            }
            String text = Files.readString(buildFile, StandardCharsets.UTF_8).toLowerCase();
            forbidden.stream()
                    .filter(text::contains)
                    .filter(term -> !allowedHostApiDependency(normalized, term))
                    .map(term -> normalized + " contains " + term)
                    .forEach(violations::add);
        }
        assertTrue(violations.isEmpty(), () -> "Forbidden runtime clients in core/host build files: " + violations);
    }

    private static boolean allowedHostApiDependency(String normalizedBuildFile, String term) {
        return (normalizedBuildFile.equals("host/paper-agent/build.gradle.kts") && term.equals("paper-api"))
                || (normalizedBuildFile.equals("host/velocity-agent/build.gradle.kts") && term.equals("velocity-api"));
    }

    @Test
    void noSelfCreatedTablePathsInProductionSource() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
            String normalized = ROOT.relativize(source).toString().replace('\\', '/');
            boolean generatedMigrationCode = normalized.startsWith("data/contract-codegen/");
            if (text.contains("ensuretable") || (!generatedMigrationCode && text.contains("create table"))) {
                violations.add(ROOT.relativize(source).toString());
            }
        }
        assertTrue(violations.isEmpty(), () -> "Self-created table paths found: " + violations);
    }

    @Test
    void noCentralFeatureCommandEnumInProductionSource() throws IOException {
        List<String> violations = new ArrayList<>();
        Pattern centralCommandEnum = Pattern.compile("enum\\s+(CommandType|.*Feature.*Command.*|.*Domain.*Command.*)");
        for (Path source : productionJavaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (centralCommandEnum.matcher(text).find()) {
                violations.add(ROOT.relativize(source).toString());
            }
        }
        assertTrue(violations.isEmpty(), () -> "Central feature command enum found: " + violations);
    }

    @Test
    void terminologyConformanceForImplementationFiles() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbiddenPhrases = List.of(
                "legacy path",
                "migration shim",
                "compatibility mode",
                "fast-restore",
                "fast restore"
        );
        for (Path file : implementationTextFiles()) {
            String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase();
            forbiddenPhrases.stream()
                    .filter(text::contains)
                    .map(phrase -> ROOT.relativize(file) + " contains " + phrase)
                    .forEach(violations::add);
        }
        assertTrue(violations.isEmpty(), () -> "Terminology violations: " + violations);
    }

    @Test
    void physicalShapeDescriptorsKeepSameSemanticContracts() throws IOException {
        Path profiles = ROOT.resolve("distribution/profiles/src/main/resources/fulcrum/profiles");
        List<Path> descriptors;
        try (Stream<Path> files = Files.list(profiles)) {
            descriptors = files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }

        assertEquals(3, descriptors.size(), "Expected single-machine, small-production, and large-production descriptors");
        for (Path descriptor : descriptors) {
            String text = Files.readString(descriptor, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"semanticModel\": \"fulcrum-v2-substrate\""), descriptor + " changes semantic model");
            assertTrue(text.contains("\"contractSet\": \"fulcrum-step0-contracts\""), descriptor + " changes contract set");
            assertTrue(text.contains("\"agonesMode\""), descriptor + " must declare allocation adapter mode");
            assertTrue(text.contains("\"objectStorage\""), descriptor + " must declare object storage shape");
        }
    }

    @Test
    void serviceLauncherBuildsRunnableDistributionEntrypoints() throws IOException {
        Path buildFile = ROOT.resolve("distribution/service-launcher/build.gradle.kts");
        Path launcher = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/FulcrumLauncher.java");
        Path roles = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/LaunchRole.java");
        Path registry = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeEntrypointRegistry.java");
        Path supervisor = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/FulcrumRuntimeSupervisor.java");
        Path managedService = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ManagedRuntimeService.java");
        Path connectionSettings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeConnectionSettings.java");
        Path externalClients = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeExternalClients.java");
        Path serviceEngine = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeServiceEngine.java");
        Path runtimeServiceEngines = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeServiceEngines.java");
        Path authorityEngine = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/AuthorityRuntimeServiceEngine.java");
        Path controllerEngine = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ControllerRuntimeServiceEngine.java");
        Path workerEngine = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/WorkerRuntimeServiceEngine.java");
        Path authorityBindings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/AuthorityRuntimeBindings.java");
        Path externalAuthorityBindings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ExternalAuthorityRuntimeBindings.java");
        Path authorityCatalog = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/AuthorityWorkerCatalog.java");
        Path controllerCatalog = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ControllerWorkerCatalog.java");
        Path workerCatalog = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/WorkerJobCatalog.java");
        Path workerObjectHandler = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/WorkerJobObjectHandler.java");
        Path externalWorker = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ExternalWorkerJobWorker.java");
        Path workerWireCodec = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/WorkerJobWireCodec.java");
        Path authorityWorkerBinding = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/AuthorityWorkerBinding.java");
        Path controllerWorkerBinding = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/ControllerWorkerBinding.java");
        Path workerJobBinding = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/WorkerJobBinding.java");
        Path localAuthorityBindings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/LocalAuthorityRuntimeBindings.java");
        Path localControllerBindings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/LocalControllerRuntimeBindings.java");
        Path localWorkerBindings = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/LocalWorkerRuntimeBindings.java");
        Path probes = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeProbeServer.java");
        Path identityIssuer = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeIdentityIssuer.java");

        assertTrue(Files.exists(buildFile), "service launcher build file must exist");
        assertTrue(Files.exists(launcher), "service launcher main class must exist");
        assertTrue(Files.exists(roles), "service launcher roles must exist");
        assertTrue(Files.exists(registry), "service launcher entrypoint registry must exist");
        assertTrue(Files.exists(supervisor), "service launcher supervisor must exist");
        assertTrue(Files.exists(managedService), "service launcher managed runtime service must exist");
        assertTrue(Files.exists(connectionSettings), "service launcher runtime connection settings must exist");
        assertTrue(Files.exists(externalClients), "service launcher runtime external client inventory must exist");
        assertTrue(Files.exists(serviceEngine), "service launcher runtime service engine must exist");
        assertTrue(Files.exists(runtimeServiceEngines), "service launcher runtime service engine factory must exist");
        assertTrue(Files.exists(authorityEngine), "authority-service runtime engine must exist");
        assertTrue(Files.exists(controllerEngine), "controller-service runtime engine must exist");
        assertTrue(Files.exists(workerEngine), "worker-agent runtime engine must exist");
        assertTrue(Files.exists(authorityBindings), "authority-service runtime binding ports must exist");
        assertTrue(Files.exists(externalAuthorityBindings), "authority-service external runtime bindings must exist");
        assertTrue(Files.exists(authorityCatalog), "authority-service worker catalog must exist");
        assertTrue(Files.exists(controllerCatalog), "controller-service worker catalog must exist");
        assertTrue(Files.exists(workerCatalog), "worker-agent job catalog must exist");
        assertTrue(Files.exists(workerObjectHandler), "worker-agent object result handler must exist");
        assertTrue(Files.exists(externalWorker), "worker-agent external job worker must exist");
        assertTrue(Files.exists(workerWireCodec), "worker-agent job wire codec must exist");
        assertTrue(Files.exists(authorityWorkerBinding), "authority-service worker binding must exist");
        assertTrue(Files.exists(controllerWorkerBinding), "controller-service worker binding must exist");
        assertTrue(Files.exists(workerJobBinding), "worker-agent job binding must exist");
        assertTrue(Files.exists(localAuthorityBindings), "authority-service local runtime bindings must exist");
        assertTrue(Files.exists(localControllerBindings), "controller-service local runtime bindings must exist");
        assertTrue(Files.exists(localWorkerBindings), "worker-agent local runtime bindings must exist");
        assertTrue(Files.exists(probes), "service launcher probes must exist");
        assertTrue(Files.exists(identityIssuer), "service launcher identity issuer must exist");

        String buildText = Files.readString(buildFile, StandardCharsets.UTF_8);
        assertTrue(buildText.contains("application"), "service launcher must use Gradle application packaging");
        assertTrue(buildText.contains("mainClass.set(\"sh.harold.fulcrum.distribution.launcher.FulcrumLauncher\")"),
                "service launcher must declare the runtime main class");
        assertTrue(buildText.contains("installDist"), "service launcher check must build installable start scripts");
        assertTrue(buildText.contains("implementation(project(\":data:authority-runtime\"))"),
                "authority-service must compile against the production authority runtime worker API");
        assertTrue(buildText.contains("implementation(project(\":adapters:agones-allocator\"))"),
                "controller-service must compile against the Agones allocation adapter");
        assertTrue(buildText.contains("implementation(project(\":adapters:object-storage\"))"),
                "worker-agent must compile against the object storage adapter");
        assertTrue(buildText.contains("implementation(project(\":control:allocation-bridge\"))"),
                "controller-service must compile against the allocation bridge");
        assertTrue(buildText.contains("implementation(project(\":control:capability-backend-registration\"))"),
                "controller-service must expose the generic capability backend registration authority");
        for (String storeModule : List.of(
                ":data:store-kafka",
                ":data:store-postgresql",
                ":data:store-cassandra",
                ":data:store-valkey")) {
            assertTrue(buildText.contains("implementation(project(\"" + storeModule + "\"))"),
                    "authority-service must compile against " + storeModule);
        }
        for (String authorityModule : List.of(
                ":data:subject-authority",
                ":data:presence-authority",
                ":data:route-authority",
                ":data:session-authority",
                ":data:artifact-authority")) {
            assertTrue(buildText.contains("implementation(project(\"" + authorityModule + "\"))"),
                    "authority-service must compile against " + authorityModule);
        }
        for (String controllerModule : List.of(
                ":control:instance-registry-controller",
                ":control:route-controller",
                ":control:lifecycle-controller",
                ":control:capability-enablement-controller",
                ":control:queue-controller",
                ":control:fault-controller")) {
            assertTrue(buildText.contains("implementation(project(\"" + controllerModule + "\"))"),
                    "controller-service must compile against " + controllerModule);
        }

        String launcherText = Files.readString(launcher, StandardCharsets.UTF_8);
        assertTrue(launcherText.contains("public static void main"), "service launcher must expose a main method");
        assertTrue(launcherText.contains("FulcrumRuntimeSupervisor"), "run mode must start the supervisor");

        String registryText = Files.readString(registry, StandardCharsets.UTF_8);
        String roleText = Files.readString(roles, StandardCharsets.UTF_8);
        String managedServiceText = Files.readString(managedService, StandardCharsets.UTF_8);
        String connectionSettingsText = Files.readString(connectionSettings, StandardCharsets.UTF_8);
        String externalClientsText = Files.readString(externalClients, StandardCharsets.UTF_8);
        String runtimeServiceEnginesText = Files.readString(runtimeServiceEngines, StandardCharsets.UTF_8);
        String authorityEngineText = Files.readString(authorityEngine, StandardCharsets.UTF_8);
        String authorityCatalogText = Files.readString(authorityCatalog, StandardCharsets.UTF_8);
        String externalAuthorityBindingsText = Files.readString(externalAuthorityBindings, StandardCharsets.UTF_8);
        String controllerCatalogText = Files.readString(controllerCatalog, StandardCharsets.UTF_8);
        String workerCatalogText = Files.readString(workerCatalog, StandardCharsets.UTF_8);
        String workerObjectHandlerText = Files.readString(workerObjectHandler, StandardCharsets.UTF_8);
        String externalWorkerText = Files.readString(externalWorker, StandardCharsets.UTF_8);
        String workerWireCodecText = Files.readString(workerWireCodec, StandardCharsets.UTF_8);
        String authorityWorkerBindingText = Files.readString(authorityWorkerBinding, StandardCharsets.UTF_8);
        String controllerWorkerBindingText = Files.readString(controllerWorkerBinding, StandardCharsets.UTF_8);
        String workerJobBindingText = Files.readString(workerJobBinding, StandardCharsets.UTF_8);
        String localAuthorityBindingsText = Files.readString(localAuthorityBindings, StandardCharsets.UTF_8);
        String localControllerBindingsText = Files.readString(localControllerBindings, StandardCharsets.UTF_8);
        String localWorkerBindingsText = Files.readString(localWorkerBindings, StandardCharsets.UTF_8);
        for (String role : List.of("authority-service", "controller-service", "worker-agent", "paper-agent", "velocity-agent")) {
            assertTrue(roleText.contains(role), "service launcher missing entrypoint role " + role);
        }
        for (String roleConstant : List.of("AUTHORITY_SERVICE", "CONTROLLER_SERVICE", "WORKER_AGENT", "PAPER_AGENT", "VELOCITY_AGENT")) {
            assertTrue(registryText.contains(roleConstant), "service launcher registry missing role " + roleConstant);
        }
        String supervisorText = Files.readString(supervisor, StandardCharsets.UTF_8);
        String probeText = Files.readString(probes, StandardCharsets.UTF_8);
        String identityText = Files.readString(identityIssuer, StandardCharsets.UTF_8);
        assertTrue(supervisorText.contains("ManagedRuntimeService"), "supervisor must own managed role services");
        assertTrue(supervisorText.contains("RuntimeConnectionSettings.resolve"),
                "supervisor must resolve typed runtime connection settings before startup");
        assertTrue(supervisorText.contains("RuntimeExternalClients.create"),
                "supervisor must construct role-scoped runtime external clients before service startup");
        assertTrue(managedServiceText.contains("RuntimeServiceEngine"), "managed runtime service must delegate to runtime engines");
        assertTrue(managedServiceText.contains("RuntimeExternalClients"),
                "managed runtime service must pass external clients into runtime engines");
        for (String binding : List.of(
                "FULCRUM_KAFKA_BOOTSTRAP_SERVERS",
                "FULCRUM_POSTGRES_JDBC_URL",
                "FULCRUM_CASSANDRA_CONTACT_POINTS",
                "FULCRUM_CASSANDRA_LOCAL_DATACENTER",
                "FULCRUM_VALKEY_ENDPOINT",
                "FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS",
                "FULCRUM_AGONES_ALLOCATOR_URL",
                "FULCRUM_AGONES_NAMESPACE",
                "FULCRUM_AUTHORITY_REGISTRATION_BIND_HOST",
                "FULCRUM_AUTHORITY_REGISTRATION_PORT",
                "FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS",
                "FULCRUM_WORKER_OBJECT_BUCKET",
                "FULCRUM_OBJECT_STORE_ROOT")) {
            assertTrue(connectionSettingsText.contains(binding), "runtime connection settings missing " + binding);
        }
        assertTrue(connectionSettingsText.contains("password=<redacted>"),
                "runtime connection settings must redact secret material in summaries");
        for (String clientHandle : List.of(
                "KafkaClientBundle",
                "PostgresClientHandle",
                "CassandraClientHandle",
                "ValkeyClientHandle",
                "LocalObjectStorageAdapter",
                "AgonesAllocatorRestClient")) {
            assertTrue(externalClientsText.contains(clientHandle),
                    "runtime external clients must construct " + clientHandle);
        }
        for (String forbiddenConstructor : List.of(
                "new KafkaProducer",
                "new KafkaConsumer",
                "PGSimpleDataSource",
                "CqlSession.builder",
                "new UnifiedJedis")) {
            assertFalse(externalClientsText.contains(forbiddenConstructor),
                    "service-launcher must use store adapter client handles instead of " + forbiddenConstructor);
        }
        assertTrue(runtimeServiceEnginesText.contains("externalClients"),
                "runtime engine factory must use the external client inventory");
        assertTrue(runtimeServiceEnginesText.contains("ExternalAuthorityRuntimeBindings"),
                "authority-service must run through external authority bindings in run mode");
        for (String adapterPort : List.of(
                "KafkaAuthorityCommandSource",
                "JdbcAuthorityRecordStore",
                "CassandraAuthorityProjectionWriter",
                "ValkeyIdempotencyLedger",
                "KafkaAuthorityOffsetCommitter")) {
            assertTrue(externalAuthorityBindingsText.contains(adapterPort),
                    "external authority bindings must compose " + adapterPort);
        }
        assertTrue(authorityEngineText.contains("AuthorityWorkerBinding"), "authority-service must run worker bindings");
        assertTrue(authorityWorkerBindingText.contains("AuthorityRuntimeReceipt"),
                "authority-service worker binding must expose authority runtime receipts");
        assertTrue(authorityWorkerBindingText.contains("AuthorityRuntimeWorker"),
                "authority-service worker binding must wrap production authority runtime workers");
        for (String domain : List.of("subject", "presence", "route", "session", "artifact-metadata")) {
            assertTrue(authorityCatalogText.contains(domain), "authority-service catalog missing " + domain);
        }
        assertTrue(controllerWorkerBindingText.contains("ControllerRuntimeReceipt"),
                "controller-service worker binding must expose controller runtime receipts");
        for (String domain : List.of(
                "instance-registry",
                "route-attempt",
                "experience-session",
                "lifecycle-trace",
                "capability-enablement",
                "queue-roster",
                "fault",
                "shared-shard-allocation")) {
            assertTrue(controllerCatalogText.contains(domain), "controller-service catalog missing " + domain);
        }
        assertTrue(controllerCatalogText.contains("SharedShardAllocationBridge"),
                "controller-service catalog must wrap the allocation bridge");
        assertTrue(localControllerBindingsText.contains("SharedShardAllocationDecision"),
                "local controller bindings must capture shared-shard allocation decisions");
        assertTrue(managedServiceText.contains("RuntimeConnectionSettings"),
                "managed runtime service must pass resolved runtime settings into engines");
        assertTrue(workerJobBindingText.contains("WorkerAgentRuntime"),
                "worker-agent job binding must wrap production worker runtime");
        assertTrue(workerCatalogText.contains("WorkerJobKind"),
                "worker-agent catalog must enumerate worker job kinds");
        assertTrue(workerCatalogText.contains("WorkerJobObjectHandler"),
                "worker-agent catalog must route local jobs through the shared object result handler");
        assertTrue(workerObjectHandlerText.contains("ObjectStorageAdapter"),
                "worker-agent object handler must write job results through object storage");
        assertTrue(workerObjectHandlerText.contains("ArtifactPin"),
                "worker-agent object handler must content-address worker job results");
        assertTrue(runtimeServiceEnginesText.contains("ExternalWorkerJobWorker"),
                "worker-agent runtime must include an external worker job topic poller");
        assertTrue(externalWorkerText.contains("commitSync"),
                "worker-agent external worker must commit consumed job offsets");
        assertTrue(externalWorkerText.contains("WorkerJobWireCodec.encodeReceipt"),
                "worker-agent external worker must publish durable worker result records");
        assertTrue(workerWireCodecText.contains("WorkerJobRequest"),
                "worker-agent wire codec must decode typed worker job requests");
        assertTrue(runtimeServiceEnginesText.contains("workerClients.objectStorage"),
                "worker-agent runtime must receive constructed object storage from external clients");
        assertTrue(localAuthorityBindingsText.contains("InMemoryIdempotencyLedger"),
                "local authority bindings must preserve idempotency semantics for single-machine runtime");
        assertTrue(localControllerBindingsText.contains("ConcurrentLinkedQueue"),
                "local controller bindings must expose process-loop queues for single-machine runtime");
        assertTrue(localWorkerBindingsText.contains("WorkerJobReceipt"),
                "local worker bindings must capture worker runtime receipts");
        assertTrue(probeText.contains("/live"), "probe server must expose liveness");
        assertTrue(probeText.contains("/ready"), "probe server must expose readiness");
        assertTrue(identityText.contains("HostSecurityContext"), "identity issuer must produce scoped host security context");
    }

    @Test
    void edgeIngressAndRoutingDecisionIsCapturedInAdr() throws IOException {
        Path adr = ADR_ROOT.resolve("ADR-0017-edge-ingress-and-routing.md");
        assertTrue(Files.exists(adr), "edge ingress and routing ADR must exist");

        String text = Files.readString(adr, StandardCharsets.UTF_8);
        for (String required : List.of(
                "L4 TCP load balancing",
                "Velocity Instances",
                "RouteController",
                "Paper Instances are not public ingress targets",
                "Presence, Route, and Session projections"
        )) {
            assertTrue(text.contains(required), "ADR missing required decision text: " + required);
        }
    }

    @Test
    void lobbyBringupAdrsExistBeforeHostIntegration() throws IOException {
        Map<String, List<String>> adrTerms = Map.of(
                "ADR-0018-shared-shard-placement.md",
                List.of("shared-shard", "many Presences", "Agones Fleet", "ResolvedManifest"),
                "ADR-0019-host-credential-issuance.md",
                List.of("least-privilege", "Host runtimes", "canonical stores", "transport principal"),
                "ADR-0020-cluster-e2e-strategy.md",
                List.of("clusterE2e", "headless Minecraft", "L4", "Agones")
        );
        for (Map.Entry<String, List<String>> entry : adrTerms.entrySet()) {
            Path adr = ADR_ROOT.resolve(entry.getKey());
            assertTrue(Files.exists(adr), entry.getKey() + " must exist");
            String text = Files.readString(adr, StandardCharsets.UTF_8);
            for (String required : entry.getValue()) {
                assertTrue(text.contains(required), entry.getKey() + " missing " + required);
            }
        }
    }

    @Test
    void storeAdapterCertificationMatrixExists() throws IOException {
        Path matrix = ROOT.resolve("validation/store-adapter-certification/src/main/resources/fulcrum/validation/store-adapter-certification.md");
        assertTrue(Files.exists(matrix), "store adapter certification matrix must exist");

        String text = Files.readString(matrix, StandardCharsets.UTF_8);
        for (String required : List.of("kafka-log", "cassandra-projection", "postgresql-authority-record", "valkey-cache-idempotency", "object-storage-artifact")) {
            assertTrue(text.contains(required), "store adapter certification matrix missing " + required);
        }
    }

    @Test
    void concreteStoreAdaptersOwnPhysicalClients() throws IOException {
        assertSourceContains("data/store-kafka/src/main/java", "org.apache.kafka.clients.consumer", "org.apache.kafka.clients.producer");
        assertSourceContains("data/store-cassandra/src/main/java", "CqlSession");
        assertSourceContains("data/store-postgresql/src/main/java", "DataSource", "PreparedStatement");
        assertSourceContains("data/store-valkey/src/main/java", "UnifiedJedis");
        assertSourceContains("adapters/object-storage/src/main/java", "LocalObjectStorageAdapter", "ArtifactBlobLayout");
    }

    @Test
    void artifactAuthorityStaysMetadataOnly() throws IOException {
        Path artifactAuthority = ROOT.resolve("data/artifact-authority/src/main/java");
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "ResolvedManifest",
                "placement",
                "rotation",
                "operator",
                "loot",
                "shop",
                "quest",
                "team size",
                "map rotation"
        );
        if (!Files.exists(artifactAuthority)) {
            return;
        }
        try (Stream<Path> files = Files.walk(artifactAuthority)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Artifact authority crossed metadata boundary: " + violations);
    }

    @Test
    void routeAuthorityStaysRouteOnly() throws IOException {
        Path routeAuthority = ROOT.resolve("data/route-authority/src/main/java");
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats",
                "loot",
                "kit",
                "reward"
        );
        if (!Files.exists(routeAuthority)) {
            return;
        }
        try (Stream<Path> files = Files.walk(routeAuthority)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Route authority crossed route boundary: " + violations);
    }

    @Test
    void sessionAuthorityStaysPlatformLifecycleOnly() throws IOException {
        Path sessionAuthority = ROOT.resolve("data/session-authority/src/main/java");
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "paper",
                "bukkit",
                "velocity",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats",
                "loot",
                "kit",
                "reward"
        );
        if (!Files.exists(sessionAuthority)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sessionAuthority)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Session authority crossed platform lifecycle boundary: " + violations);
    }

    @Test
    void subjectAuthorityStaysThinIdentityOnly() throws IOException {
        Path subjectAuthority = ROOT.resolve("data/subject-authority/src/main/java");
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "presence",
                "sessionid",
                "route",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats",
                "realm",
                "cosmetic",
                "setting",
                "loot",
                "kit",
                "reward"
        );
        if (!Files.exists(subjectAuthority)) {
            return;
        }
        try (Stream<Path> files = Files.walk(subjectAuthority)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Subject authority crossed thin identity boundary: " + violations);
    }

    @Test
    void authorityRuntimeDeclaresPortsWithoutConcreteStoreClients() throws IOException {
        Path authorityRuntime = ROOT.resolve("data/authority-runtime/src/main/java");
        if (!Files.exists(authorityRuntime)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "KafkaConsumer",
                "KafkaProducer",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "com.datastax",
                "io.lettuce",
                "redis",
                "create table"
        );
        try (Stream<Path> files = Files.walk(authorityRuntime)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(term -> text.toLowerCase().contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Authority runtime crossed adapter boundary: " + violations);
    }

    @Test
    void hostApiKeepsCredentialScopeReadOrMessageOnly() throws IOException {
        Path accessMode = ROOT.resolve("host/host-api/src/main/java/sh/harold/fulcrum/host/api/HostAccessMode.java");
        String text = Files.readString(accessMode, StandardCharsets.UTF_8);

        assertTrue(text.contains("PRODUCE"), "Host API must allow scoped command or observation production");
        assertTrue(text.contains("CONSUME"), "Host API must allow scoped addressed command consumption");
        assertTrue(text.contains("READ"), "Host API must allow scoped hot projection, cache, and artifact reads");
        assertTrue(!text.contains("WRITE"), "Host API must not expose canonical store write grants");
    }

    @Test
    void authoritySdkStaysOnPublishedBackendBoundary() throws IOException {
        Path sdkBuild = ROOT.resolve("sdk/authority-sdk/build.gradle.kts");
        String sdkText = Files.readString(sdkBuild, StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();
        List<String> forbiddenBuildEdges = List.of(":control:", ":data:store-", ":distribution:");
        forbiddenBuildEdges.stream()
                .filter(sdkText::contains)
                .map(edge -> "sdk/authority-sdk/build.gradle.kts contains " + edge)
                .forEach(violations::add);

        Path sdkSource = ROOT.resolve("sdk/authority-sdk/src/main/java");
        List<String> forbiddenImports = List.of(
                "sh.harold.fulcrum.control.",
                "sh.harold.fulcrum.data.store.",
                "sh.harold.fulcrum.distribution.");
        try (Stream<Path> files = Files.walk(sdkSource)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbiddenImports.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }

        assertTrue(violations.isEmpty(), () -> "Authority SDK crossed author-facing boundary: " + violations);
    }

    @Test
    void authoringSdkStaysOnPublishedAuthorBoundary() throws IOException {
        Path sdkBuild = ROOT.resolve("sdk/authoring-sdk/build.gradle.kts");
        String sdkText = Files.readString(sdkBuild, StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();
        List<String> forbiddenBuildEdges = List.of(":control:", ":data:store-", ":distribution:");
        forbiddenBuildEdges.stream()
                .filter(sdkText::contains)
                .map(edge -> "sdk/authoring-sdk/build.gradle.kts contains " + edge)
                .forEach(violations::add);

        Path sdkSource = ROOT.resolve("sdk/authoring-sdk/src/main/java");
        List<String> forbiddenImports = List.of(
                "sh.harold.fulcrum.control.",
                "sh.harold.fulcrum.data.store.",
                "sh.harold.fulcrum.distribution.");
        try (Stream<Path> files = Files.walk(sdkSource)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbiddenImports.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }

        assertTrue(violations.isEmpty(), () -> "Authoring SDK crossed author-facing boundary: " + violations);
    }

    @Test
    void noOpBackendConformanceMainUsesOnlyPublishedSdk() throws IOException {
        Path buildFile = ROOT.resolve("validation/authority-sdk-conformance/build.gradle.kts");
        String buildText = Files.readString(buildFile, StandardCharsets.UTF_8);
        assertTrue(buildText.contains("api(project(\":sdk:authority-sdk\"))"),
                "no-op backend conformance main code must compile against the published SDK");
        assertTrue(buildText.contains("testImplementation(project(\":control:capability-backend-registration\"))"),
                "no-op backend conformance tests may wire the registration control authority");

        Path mainSource = ROOT.resolve("validation/authority-sdk-conformance/src/main/java");
        List<String> violations = new ArrayList<>();
        List<String> forbiddenImports = List.of(
                "sh.harold.fulcrum.control.",
                "sh.harold.fulcrum.data.store.",
                "sh.harold.fulcrum.distribution.");
        try (Stream<Path> files = Files.walk(mainSource)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbiddenImports.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "No-op backend main source imported private substrate internals: " + violations);
    }

    @Test
    void fakeAgonesAdapterStaysAllocationOnly() throws IOException {
        Path fakeAgones = ROOT.resolve("adapters/agones-fake/src/main/java");
        if (!Files.exists(fakeAgones)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "create table"
        );
        try (Stream<Path> files = Files.walk(fakeAgones)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Fake Agones adapter crossed allocation boundary: " + violations);
    }

    @Test
    void agonesAllocatorAdapterStaysAllocationOnly() throws IOException {
        Path agonesAllocator = ROOT.resolve("adapters/agones-allocator/src/main/java");
        if (!Files.exists(agonesAllocator)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "io.kubernetes",
                "KubernetesClient",
                "FleetAutoscaler",
                "reconcile",
                "watch",
                "create table"
        );
        try (Stream<Path> files = Files.walk(agonesAllocator)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Agones allocator adapter crossed allocation boundary: " + violations);
    }

    @Test
    void paperAgentArtifactCacheDoesNotUseCanonicalStoreOrLogClients() throws IOException {
        Path paperAgent = ROOT.resolve("host/paper-agent/src/main/java");
        if (!Files.exists(paperAgent)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "create table"
        );
        try (Stream<Path> files = Files.walk(paperAgent)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Paper agent artifact cache crossed host boundary: " + violations);
    }

    @Test
    void velocityAgentUsesRouteContractsWithoutAuthorityRuntimeOrStores() throws IOException {
        Path velocityAgent = ROOT.resolve("host/velocity-agent/src/main/java");
        if (!Files.exists(velocityAgent)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "AuthorityCommand",
                "AuthorityCommandProcessor",
                "IdempotencyLedger",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "create table"
        );
        try (Stream<Path> files = Files.walk(velocityAgent)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Velocity agent crossed route command boundary: " + violations);
    }

    @Test
    void effectAdmissionHasNoExecutorStoreOrAdapterClients() throws IOException {
        Path effectAdmission = ROOT.resolve("host/effect-admission/src/main/java");
        if (!Files.exists(effectAdmission)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "AuthorityCommandProcessor",
                "IdempotencyLedger",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HttpClient",
                "ExecutorService",
                "create table"
        );
        try (Stream<Path> files = Files.walk(effectAdmission)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Effect admission crossed host boundary: " + violations);
    }

    @Test
    void sessionRuntimeCoreHasNoHostRuntimeOrBlockingDependencies() throws IOException {
        Path sessionRuntime = ROOT.resolve("core/session-runtime/src/main/java");
        if (!Files.exists(sessionRuntime)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "java.net",
                "java.sql",
                "java.io",
                "HttpClient",
                "Thread.sleep",
                "CompletableFuture",
                "Future<",
                "ExecutorService",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "create table"
        );
        try (Stream<Path> files = Files.walk(sessionRuntime)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Session runtime core crossed reducer boundary: " + violations);
    }

    @Test
    void hostTickRuntimeApiHasNoPaperOrStoreClients() throws IOException {
        Path tickRuntime = ROOT.resolve("host/tick-runtime-api/src/main/java");
        if (!Files.exists(tickRuntime)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "AuthorityCommandProcessor",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "create table"
        );
        try (Stream<Path> files = Files.walk(tickRuntime)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Host tick runtime API crossed host boundary: " + violations);
    }

    @Test
    void workerAgentBaseHasNoGameOrStoreClients() throws IOException {
        Path workerAgent = ROOT.resolve("host/worker-agent/src/main/java");
        if (!Files.exists(workerAgent)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HttpClient",
                "Thread.sleep",
                "CompletableFuture",
                "Future<",
                "ExecutorService",
                "AuthorityCommandProcessor",
                "create table"
        );
        try (Stream<Path> files = Files.walk(workerAgent)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Worker agent base crossed host boundary: " + violations);
    }

    @Test
    void capabilityRuntimeHasNoHostStoreOrAdapterClients() throws IOException {
        Path capabilityRuntime = ROOT.resolve("capability/capability-runtime/src/main/java");
        if (!Files.exists(capabilityRuntime)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HttpClient",
                "HostAllocationPort",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "create table"
        );
        try (Stream<Path> files = Files.walk(capabilityRuntime)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Capability runtime crossed materialization boundary: " + violations);
    }

    @Test
    void sessionRuntimeDoesNotImplementDeferredMinigameEngine() throws IOException {
        List<Path> runtimeRoots = List.of(
                ROOT.resolve("core/session-runtime/src/main/java"),
                ROOT.resolve("host/tick-runtime-api/src/main/java")
        );

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "minigame",
                "pregame",
                "gameplay",
                "waiting lobby",
                "participant",
                "spectator",
                "team roster",
                "kit",
                "loot table"
        );
        for (Path runtimeRoot : runtimeRoots) {
            if (!Files.exists(runtimeRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(runtimeRoot)) {
                for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                    forbidden.stream()
                            .filter(text::contains)
                            .map(term -> ROOT.relativize(source) + " contains " + term)
                            .forEach(violations::add);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Session runtime implemented deferred engine concepts: " + violations);
    }

    @Test
    void routeControllerStaysControlPlaneOnly() throws IOException {
        Path routeController = ROOT.resolve("control/route-controller/src/main/java");
        if (!Files.exists(routeController)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FleetAutoscaler",
                "fleet reconciliation",
                "warm buffer",
                "rollout",
                "health integration",
                "create table"
        );
        try (Stream<Path> files = Files.walk(routeController)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Route controller crossed control-plane boundary: " + violations);
    }

    @Test
    void instanceRegistryControllerStaysOverlayOnly() throws IOException {
        Path instanceRegistry = ROOT.resolve("control/instance-registry-controller/src/main/java");
        if (!Files.exists(instanceRegistry)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "GameServerAllocation",
                "FleetAutoscaler",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats"
        );
        try (Stream<Path> files = Files.walk(instanceRegistry)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Instance registry crossed overlay boundary: " + violations);
    }

    @Test
    void capabilityEnablementControllerStaysControlPlaneOnly() throws IOException {
        Path capabilityEnablement = ROOT.resolve("control/capability-enablement-controller/src/main/java");
        if (!Files.exists(capabilityEnablement)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "GameServerAllocation",
                "FleetAutoscaler",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "player-profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "economy",
                "auction",
                "stats"
        );
        try (Stream<Path> files = Files.walk(capabilityEnablement)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Capability enablement crossed control-plane boundary: " + violations);
    }

    @Test
    void queueControllerStaysControlPlaneOnly() throws IOException {
        Path queueController = ROOT.resolve("control/queue-controller/src/main/java");
        if (!Files.exists(queueController)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FleetAutoscaler",
                "fleet reconciliation",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats"
        );
        try (Stream<Path> files = Files.walk(queueController)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Queue controller crossed control-plane boundary: " + violations);
    }

    @Test
    void allocationBridgeStaysPortOnly() throws IOException {
        Path allocationBridge = ROOT.resolve("control/allocation-bridge/src/main/java");
        if (!Files.exists(allocationBridge)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "FleetAutoscaler",
                "fleet reconciliation",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats"
        );
        try (Stream<Path> files = Files.walk(allocationBridge)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Allocation bridge crossed port boundary: " + violations);
    }

    @Test
    void faultControllerStaysOverlayOnly() throws IOException {
        Path faultController = ROOT.resolve("control/fault-controller/src/main/java");
        if (!Files.exists(faultController)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "FleetAutoscaler",
                "fleet reconciliation",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats"
        );
        try (Stream<Path> files = Files.walk(faultController)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Fault controller crossed overlay boundary: " + violations);
    }

    @Test
    void lifecycleControllerStaysTraceAndSessionOnly() throws IOException {
        Path lifecycleController = ROOT.resolve("control/lifecycle-controller/src/main/java");
        if (!Files.exists(lifecycleController)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of(
                "io.papermc",
                "org.bukkit",
                "com.velocitypowered",
                "net.minecraft",
                "io.kubernetes",
                "KubernetesClient",
                "Kafka",
                "Cassandra",
                "PostgreSQL",
                "Valkey",
                "java.sql",
                "HostAllocationPort",
                "FakeAgonesAllocationAdapter",
                "AgonesAllocatorRestClient",
                "FleetAutoscaler",
                "fleet reconciliation",
                "warm buffer",
                "rollout",
                "health integration",
                "create table",
                "rank",
                "profile",
                "punishment",
                "party",
                "guild",
                "friends",
                "chat",
                "economy",
                "stats"
        );
        try (Stream<Path> files = Files.walk(lifecycleController)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
                forbidden.stream()
                        .filter(term -> text.contains(term.toLowerCase()))
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Lifecycle controller crossed trace/session boundary: " + violations);
    }

    private static List<Path> productionJavaSources() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> ROOT.relativize(path).toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(ArchitectureValidationTest::isImplementationPath)
                    .toList();
        }
    }

    private static List<Path> implementationTextFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(ArchitectureValidationTest::isImplementationPath)
                    .filter(ArchitectureValidationTest::hasImplementationTextExtension)
                    .toList();
        }
    }

    private static List<Path> buildFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("build.gradle.kts"))
                    .filter(ArchitectureValidationTest::isImplementationPath)
                    .toList();
        }
    }

    private static List<Path> serviceLauncherDomainBlindTextFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(
                ROOT.resolve("distribution/service-launcher/build.gradle.kts"),
                ROOT.resolve("distribution/service-launcher/src/main/java"),
                ROOT.resolve("distribution/service-launcher/src/main/resources"))) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                files.add(root);
                continue;
            }
            try (Stream<Path> walked = Files.walk(root)) {
                walked.filter(Files::isRegularFile)
                        .filter(ArchitectureValidationTest::hasServiceLauncherDomainBlindTextExtension)
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static boolean hasServiceLauncherDomainBlindTextExtension(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".kts")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".json")
                || fileName.endsWith(".md")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".cql");
    }

    private static boolean hasImplementationTextExtension(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".kts")
                || fileName.endsWith(".toml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".json");
    }

    private static boolean isImplementationPath(Path path) {
        String normalized = ROOT.relativize(path).toString().replace('\\', '/');
        return !normalized.startsWith("planning/")
                && !normalized.startsWith(".git/")
                && !normalized.startsWith(".gradle/")
                && !normalized.contains("/src/test/")
                && !normalized.contains("/build/")
                && !normalized.startsWith("build/");
    }

    private static String modulePathFor(Path buildFile) {
        Path relativeParent = ROOT.relativize(buildFile.getParent());
        if (relativeParent.toString().isEmpty()) {
            return null;
        }
        String physicalPath = relativeParent.toString().replace('\\', '/');
        if (physicalPath.equals("data/contract-api")) {
            return ":data:contract-declarations";
        }
        return ":" + relativeParent.toString().replace('\\', ':').replace('/', ':');
    }

    private static void assertSourceContains(String relativeRoot, String... terms) throws IOException {
        Path root = ROOT.resolve(relativeRoot);
        assertTrue(Files.exists(root), relativeRoot + " must exist");
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                combined.append(Files.readString(source, StandardCharsets.UTF_8)).append('\n');
            }
        }
        for (String term : terms) {
            assertTrue(combined.toString().contains(term), relativeRoot + " missing " + term);
        }
    }

    private static Path findRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository root");
    }
}
