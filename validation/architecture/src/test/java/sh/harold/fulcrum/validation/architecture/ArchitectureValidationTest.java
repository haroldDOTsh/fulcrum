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
            Map.entry(":api:contract-api", Set.of(":api:kernel-api")),
            Map.entry(":api:kernel-api", Set.of()),
            Map.entry(":capability:capability-api", Set.of(":api:contract-api", ":api:kernel-api", ":data:contract-declarations")),
            Map.entry(":core:manifest-core", Set.of(":api:contract-api", ":api:kernel-api")),
            Map.entry(":data:artifact-authority", Set.of(":api:contract-api", ":data:authority-core")),
            Map.entry(":data:authority-core", Set.of(":api:contract-api")),
            Map.entry(":data:contract-codegen", Set.of(":api:contract-api", ":data:contract-declarations")),
            Map.entry(":data:contract-declarations", Set.of(":api:contract-api")),
            Map.entry(":data:presence-authority", Set.of(":api:contract-api", ":api:kernel-api", ":data:authority-core")),
            Map.entry(":distribution:profiles", Set.of()),
            Map.entry(":host:host-api", Set.of(":api:contract-api", ":api:kernel-api", ":core:manifest-core")),
            Map.entry(":platform:fulcrum-bom", Set.of()),
            Map.entry(":testkit:architecture-testkit", Set.of()),
            Map.entry(":testkit:substrate-testkit", Set.of(":data:artifact-authority", ":data:contract-codegen", ":data:presence-authority")),
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
