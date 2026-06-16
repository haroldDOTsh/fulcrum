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
            Map.entry(":distribution:service-launcher", Set.of(":adapters:agones-allocator", ":adapters:agones-fake", ":capability:capability-runtime", ":control:allocation-bridge", ":control:capability-enablement-controller", ":control:fault-controller", ":control:instance-registry-controller", ":control:lifecycle-controller", ":control:queue-controller", ":control:route-controller", ":data:artifact-authority", ":data:authority-runtime", ":data:presence-authority", ":data:route-authority", ":data:session-authority", ":data:subject-authority", ":distribution:profiles", ":host:effect-admission", ":host:paper-agent", ":host:tick-runtime-api", ":host:velocity-agent", ":host:worker-agent", ":standard-capabilities:chat-decoration", ":standard-capabilities:player-profile", ":standard-capabilities:punishment", ":standard-capabilities:rank", ":standard-capabilities:realm", ":standard-capabilities:standard-contracts")),
            Map.entry(":host:effect-admission", Set.of(":core:session-runtime", ":host:host-api")),
            Map.entry(":host:host-api", Set.of(":api:contract-api", ":api:kernel-api", ":core:manifest-core")),
            Map.entry(":host:paper-agent", Set.of(":core:artifact-layout", ":host:host-api")),
            Map.entry(":host:tick-runtime-api", Set.of(":core:session-runtime", ":host:host-api")),
            Map.entry(":host:velocity-agent", Set.of(":host:host-api", ":data:route-contract")),
            Map.entry(":host:worker-agent", Set.of(":api:contract-api", ":api:kernel-api", ":host:host-api")),
            Map.entry(":platform:fulcrum-bom", Set.of()),
            Map.entry(":standard-capabilities:chat-decoration", Set.of(":capability:capability-api", ":capability:capability-runtime", ":standard-capabilities:player-profile", ":standard-capabilities:rank", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:player-profile", Set.of(":capability:capability-api", ":capability:capability-runtime", ":data:authority-core", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:punishment", Set.of(":capability:capability-api", ":capability:capability-runtime", ":data:authority-core", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:rank", Set.of(":capability:capability-api", ":capability:capability-runtime", ":data:authority-core", ":standard-capabilities:player-profile", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:realm", Set.of(":api:contract-api", ":capability:capability-api", ":capability:capability-runtime", ":core:artifact-layout", ":core:manifest-core", ":standard-capabilities:standard-contracts")),
            Map.entry(":standard-capabilities:standard-contracts", Set.of(":api:contract-api", ":data:contract-declarations")),
            Map.entry(":testkit:architecture-testkit", Set.of()),
            Map.entry(":testkit:substrate-testkit", Set.of(":capability:capability-runtime", ":data:artifact-authority", ":data:contract-codegen", ":data:presence-authority")),
            Map.entry(":validation:architecture", Set.of()),
            Map.entry(":validation:fleet-e2e", Set.of(":adapters:agones-allocator", ":api:contract-api", ":api:kernel-api", ":capability:capability-api", ":control:allocation-bridge", ":control:queue-controller", ":control:route-controller", ":core:content-resolver", ":core:manifest-core", ":core:session-runtime", ":data:authority-core", ":data:authority-runtime", ":data:route-contract", ":data:session-authority", ":data:store-cassandra", ":data:store-kafka", ":data:store-postgresql", ":data:store-valkey", ":distribution:profiles", ":host:effect-admission", ":host:host-api", ":host:paper-agent", ":host:tick-runtime-api", ":host:velocity-agent", ":standard-capabilities:player-profile", ":standard-capabilities:punishment", ":standard-capabilities:rank", ":standard-capabilities:standard-contracts", ":testkit:substrate-testkit")),
            Map.entry(":validation:store-adapter-certification", Set.of()),
            Map.entry(":validation:synthetic-load", Set.of(":adapters:agones-fake", ":api:contract-api", ":api:kernel-api", ":control:route-controller", ":data:authority-core", ":host:host-api", ":standard-capabilities:rank")),
            Map.entry(":validation:standard-capabilities", Set.of(":capability:capability-runtime", ":standard-capabilities:chat-decoration", ":standard-capabilities:player-profile", ":standard-capabilities:punishment", ":standard-capabilities:rank", ":standard-capabilities:standard-contracts"))
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
    void serviceLauncherBuildsRunnableDistributionEntrypoints() throws IOException {
        Path buildFile = ROOT.resolve("distribution/service-launcher/build.gradle.kts");
        Path launcher = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/FulcrumLauncher.java");
        Path roles = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/LaunchRole.java");
        Path registry = ROOT.resolve("distribution/service-launcher/src/main/java/sh/harold/fulcrum/distribution/launcher/RuntimeEntrypointRegistry.java");

        assertTrue(Files.exists(buildFile), "service launcher build file must exist");
        assertTrue(Files.exists(launcher), "service launcher main class must exist");
        assertTrue(Files.exists(roles), "service launcher roles must exist");
        assertTrue(Files.exists(registry), "service launcher entrypoint registry must exist");

        String buildText = Files.readString(buildFile, StandardCharsets.UTF_8);
        assertTrue(buildText.contains("application"), "service launcher must use Gradle application packaging");
        assertTrue(buildText.contains("mainClass.set(\"sh.harold.fulcrum.distribution.launcher.FulcrumLauncher\")"),
                "service launcher must declare the runtime main class");
        assertTrue(buildText.contains("installDist"), "service launcher check must build installable start scripts");

        String launcherText = Files.readString(launcher, StandardCharsets.UTF_8);
        assertTrue(launcherText.contains("public static void main"), "service launcher must expose a main method");

        String registryText = Files.readString(registry, StandardCharsets.UTF_8);
        String roleText = Files.readString(roles, StandardCharsets.UTF_8);
        for (String role : List.of("authority-service", "controller-service", "worker-agent", "paper-agent", "velocity-agent")) {
            assertTrue(roleText.contains(role), "service launcher missing entrypoint role " + role);
        }
        for (String roleConstant : List.of("AUTHORITY_SERVICE", "CONTROLLER_SERVICE", "WORKER_AGENT", "PAPER_AGENT", "VELOCITY_AGENT")) {
            assertTrue(registryText.contains(roleConstant), "service launcher registry missing role " + roleConstant);
        }
    }

    @Test
    void edgeIngressAndRoutingDecisionIsCapturedInAdr() throws IOException {
        Path adr = ROOT.resolve("adrs/ADR-0017-edge-ingress-and-routing.md");
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
