package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandRecordBoundaryTest {
    private static final Pattern CONCRETE_COMMAND_RECORD = Pattern.compile(
        "\\bDataAuthority\\.(?:PlayerProfileCommand|PlayerSessionCommand|PlayerRankCommand|MatchCommand)\\b"
    );
    private static final Set<String> ALLOWED_COMMAND_RECORD_OWNERS = Set.of(
        "src/main/java/sh/harold/fulcrum/api/data/authority/client/AuthorityCommands.java",
        "src/main/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityCommandPayloads.java",
        "src/main/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityDomainDeclarations.java"
    );

    @Test
    void concreteCommandRecordsStayAtAdapterBoundary() throws IOException {
        Path moduleRoot = moduleRoot();
        Path sourceRoot = moduleRoot.resolve(Path.of("src", "main", "java"));
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !ALLOWED_COMMAND_RECORD_OWNERS.contains(relativeTo(moduleRoot, path)))
                .filter(AuthorityCommandRecordBoundaryTest::containsConcreteCommandRecord)
                .map(path -> relativeTo(moduleRoot, path))
                .sorted()
                .forEach(violations::add);
        }

        assertThat(violations)
            .as("Concrete authority command records must stay behind the client factory and payload/catalog adapter")
            .isEmpty();
    }

    private static boolean containsConcreteCommandRecord(Path path) {
        try {
            return CONCRETE_COMMAND_RECORD.matcher(stripJavaComments(Files.readString(path, StandardCharsets.UTF_8)))
                .find();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static Path moduleRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return "data-api".equals(current.getFileName().toString()) ? current : current.resolve("data-api");
    }

    private static String relativeTo(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String stripJavaComments(String contents) {
        return contents
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }
}
