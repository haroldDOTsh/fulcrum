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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArchitectureValidationTest {
    private static final Path ROOT = findRoot();
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("project\\(\"(:[^\"]+)\"\\)");
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
            Map.entry(":api:contract-api", Set.of(":api:kernel-api")),
            Map.entry(":api:kernel-api", Set.of()),
            Map.entry(":capability:capability-api", Set.of(":api:contract-api", ":api:kernel-api", ":data:contract-declarations")),
            Map.entry(":capability:capability-runtime", Set.of(":capability:capability-api")),
            Map.entry(":control:allocation-bridge", Set.of(":api:contract-api", ":api:kernel-api", ":control:queue-controller", ":host:host-api")),
            Map.entry(":control:fault-controller", Set.of(":api:contract-api")),
            Map.entry(":control:lifecycle-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":control:queue-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":control:route-controller", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":core:manifest-core", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":core:session-runtime", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":data:artifact-authority", Set.of(":api:contract-api", ":data:authority-core")),
            Map.entry(":data:authority-core", Set.of(":api:contract-api")),
            Map.entry(":data:contract-codegen", Set.of(":api:contract-api", ":data:contract-declarations")),
            Map.entry(":data:contract-declarations", Set.of(":api:contract-api")),
            Map.entry(":data:presence-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":data:route-contract", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":data:route-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core", ":data:route-contract")),
            Map.entry(":data:session-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":data:subject-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":distribution:profiles", Set.of()),
            Map.entry(":host:host-api", Set.of(":api:contract-api", ":api:kernel-api", ":core:manifest-core")),
            Map.entry(":host:paper-agent", Set.of(":host:host-api")),
            Map.entry(":host:tick-runtime-api", Set.of(":core:session-runtime", ":host:host-api")),
            Map.entry(":host:velocity-agent", Set.of(":host:host-api", ":data:route-contract")),
            Map.entry(":platform:fulcrum-bom", Set.of()),
            Map.entry(":standard-capabilities:chat-decoration", Set.of(":capability:capability-api", ":capability:capability-runtime", ":standard-capabilities:player-profile", ":standard-capabilities:rank", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:player-profile", Set.of(":capability:capability-api", ":capability:capability-runtime", ":data:authority-core", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:rank", Set.of(":capability:capability-api", ":capability:capability-runtime", ":data:authority-core", ":standard-capabilities:player-profile", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:standard-contracts", Set.of(":api:contract-api", ":data:contract-declarations")),
            Map.entry(":testkit:architecture-testkit", Set.of()),
            Map.entry(":testkit:substrate-testkit", Set.of(":capability:capability-runtime", ":data:artifact-authority", ":data:contract-codegen", ":data:presence-authority")),
            Map.entry(":validation:architecture", Set.of())
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
                    .map(term -> normalized + " contains " + term)
                    .forEach(violations::add);
        }
        assertTrue(violations.isEmpty(), () -> "Forbidden runtime clients in core/host build files: " + violations);
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
    void hostApiKeepsCredentialScopeReadOrMessageOnly() throws IOException {
        Path accessMode = ROOT.resolve("host/host-api/src/main/java/sh/harold/fulcrum/host/api/HostAccessMode.java");
        String text = Files.readString(accessMode, StandardCharsets.UTF_8);

        assertTrue(text.contains("PRODUCE"), "Host API must allow scoped command or observation production");
        assertTrue(text.contains("CONSUME"), "Host API must allow scoped addressed command consumption");
        assertTrue(text.contains("READ"), "Host API must allow scoped hot projection, cache, and artifact reads");
        assertTrue(!text.contains("WRITE"), "Host API must not expose canonical store write grants");
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
    void standardCapabilitiesStayOutsideKernelAndRuntimeClients() throws IOException {
        Path standardCapabilities = ROOT.resolve("standard-capabilities");
        if (!Files.exists(standardCapabilities)) {
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
                "create table"
        );
        try (Stream<Path> files = Files.walk(standardCapabilities)) {
            for (Path source : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                forbidden.stream()
                        .filter(text::contains)
                        .map(term -> ROOT.relativize(source) + " contains " + term)
                        .forEach(violations::add);
            }
        }
        assertTrue(violations.isEmpty(), () -> "Standard capabilities crossed substrate boundary: " + violations);
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
