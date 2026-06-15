package sh.harold.fulcrum.api.data.impl.postgres;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalSchemaDdlBoundaryTest {
    private static final Pattern DDL_STATEMENT = Pattern.compile(
        "(?is)\\b(?:CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX|SCHEMA|EXTENSION)|ALTER\\s+TABLE|DROP\\s+TABLE)\\b"
    );
    private static final Pattern GAME_NODE_DIRECT_POSTGRES_ACCESS = Pattern.compile(
        "(?is)\\b(?:java\\.sql|javax\\.sql|jdbc:postgresql|org\\.postgresql|com\\.zaxxer\\.hikari|"
            + "PostgresConnectionAdapter|PostgresDataAuthority|PostgresRegistryNodeSnapshotStore|"
            + "DriverManager|DataSource|postgres)\\b"
    );
    private static final Pattern HAND_ASSEMBLED_AUTHORITY_ENVELOPE = Pattern.compile(
        "(?s)(?:DataAuthority\\.CommandManifest\\.create|"
            + "new\\s+DataAuthority\\.(?:PlayerProfileCommand|PlayerSessionCommand|PlayerRankCommand|MatchCommand)\\s*\\()"
    );
    private static final Pattern RAW_AUTHORITY_COMMAND_ACTOR = Pattern.compile(
        "(?s)AuthorityCommands\\.actor\\s*\\("
    );
    private static final Pattern MESSAGE_BUS_AUTHORITY_INGRESS = Pattern.compile(
        "(?s)MessageBusDataAuthority(?:Client|Provider)|"
            + "fulcrum\\.authority\\.(?:profile|rank)\\.read|"
            + "\\b(?:PROFILE_READ|RANK_READ)\\b"
    );

    @Test
    void productionDdlLivesOnlyInDataApiMigrations() throws IOException {
        Path repoRoot = repoRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> isProductionCandidate(repoRoot, path))
                .filter(path -> !isAllowedDdlOwner(repoRoot, path))
                .filter(CanonicalSchemaDdlBoundaryTest::containsDdlStatement)
                .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .forEach(violations::add);
        }

        assertThat(violations)
            .as("Production DDL must live in data-api migrations; runtime services validate schema instead")
            .isEmpty();
    }

    @Test
    void gameNodeProductionCodeDoesNotReferenceDirectPostgresAccess() throws IOException {
        Path repoRoot = repoRoot();
        List<String> violations = new ArrayList<>();

        for (String root : List.of("runtime/src/main", "runtime-velocity/src/main")) {
            Path moduleRoot = repoRoot.resolve(root);
            if (!Files.exists(moduleRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(moduleRoot)) {
                paths.filter(Files::isRegularFile)
                    .filter(path -> hasScannedExtension(repoRoot.relativize(path).toString().replace('\\', '/')))
                    .filter(CanonicalSchemaDdlBoundaryTest::containsGameNodeDirectPostgresAccess)
                    .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(violations::add);
            }
        }

        assertThat(violations)
            .as("Game-node runtime modules must use remote DataAuthority access, not direct Postgres/JDBC clients")
            .isEmpty();
    }

    @Test
    void gameNodeProductionCodeDoesNotHandAssembleAuthorityCommandEnvelopes() throws IOException {
        Path repoRoot = repoRoot();
        List<String> violations = new ArrayList<>();

        for (String root : List.of("runtime/src/main", "runtime-velocity/src/main")) {
            Path moduleRoot = repoRoot.resolve(root);
            if (!Files.exists(moduleRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(moduleRoot)) {
                paths.filter(Files::isRegularFile)
                    .filter(path -> hasScannedExtension(repoRoot.relativize(path).toString().replace('\\', '/')))
                    .filter(CanonicalSchemaDdlBoundaryTest::containsHandAssembledAuthorityEnvelope)
                    .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(violations::add);
            }
        }

        assertThat(violations)
            .as("Game-node runtime modules must use the generated-shaped authority command facade")
            .isEmpty();
    }

    @Test
    void gameNodeProductionCodeDoesNotClaimAuthorityCommandActors() throws IOException {
        Path repoRoot = repoRoot();
        List<String> violations = new ArrayList<>();

        for (String root : List.of("runtime/src/main", "runtime-velocity/src/main")) {
            Path moduleRoot = repoRoot.resolve(root);
            if (!Files.exists(moduleRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(moduleRoot)) {
                paths.filter(Files::isRegularFile)
                    .filter(path -> hasScannedExtension(repoRoot.relativize(path).toString().replace('\\', '/')))
                    .filter(CanonicalSchemaDdlBoundaryTest::containsRawAuthorityCommandActor)
                    .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(violations::add);
            }
        }

        assertThat(violations)
            .as("Game-node runtime modules must let transport provenance stamp authority command actors")
            .isEmpty();
    }

    @Test
    void productionCodeDoesNotExposeMessageBusAuthorityCommandIngress() throws IOException {
        Path repoRoot = repoRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> isProductionCandidate(repoRoot, path))
                .filter(CanonicalSchemaDdlBoundaryTest::containsMessageBusAuthorityIngress)
                .map(path -> repoRoot.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .forEach(violations::add);
        }

        assertThat(violations)
            .as("Authority commands must enter through the durable authority log, not Redis/message-bus side doors")
            .isEmpty();
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return "data-api".equals(current.getFileName().toString()) ? current.getParent() : current;
    }

    private static boolean isProductionCandidate(Path repoRoot, Path path) {
        String relative = repoRoot.relativize(path).toString().replace('\\', '/');
        return relative.contains("/src/main/") && hasScannedExtension(relative);
    }

    private static boolean hasScannedExtension(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java")
            || lower.endsWith(".kt")
            || lower.endsWith(".kts")
            || lower.endsWith(".sql")
            || lower.endsWith(".yml")
            || lower.endsWith(".yaml")
            || lower.endsWith(".properties")
            || lower.endsWith(".xml");
    }

    private static boolean isAllowedDdlOwner(Path repoRoot, Path path) {
        String relative = repoRoot.relativize(path).toString().replace('\\', '/');
        return relative.startsWith("data-api/src/main/resources/migrations/")
            || relative.equals(
                "data-api/src/main/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresMigrationRunner.java"
            );
    }

    private static boolean containsDdlStatement(Path path) {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            return DDL_STATEMENT.matcher(stripComments(path, contents)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static boolean containsGameNodeDirectPostgresAccess(Path path) {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            return GAME_NODE_DIRECT_POSTGRES_ACCESS.matcher(stripComments(path, contents)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static boolean containsHandAssembledAuthorityEnvelope(Path path) {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            return HAND_ASSEMBLED_AUTHORITY_ENVELOPE.matcher(stripComments(path, contents)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static boolean containsRawAuthorityCommandActor(Path path) {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            return RAW_AUTHORITY_COMMAND_ACTOR.matcher(stripComments(path, contents)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static boolean containsMessageBusAuthorityIngress(Path path) {
        try {
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            return MESSAGE_BUS_AUTHORITY_INGRESS.matcher(stripComments(path, contents)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static String stripComments(Path path, String contents) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return contents
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")) {
            return contents.replaceAll("(?m)#.*$", "");
        }
        return contents;
    }
}
