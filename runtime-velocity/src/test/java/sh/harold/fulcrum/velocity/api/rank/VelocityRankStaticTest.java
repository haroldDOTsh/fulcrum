package sh.harold.fulcrum.velocity.api.rank;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityRankStaticTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    @Test
    void velocityRankUtilsUsesQuotedAuthorityReads() throws IOException {
        String rankUtils = readSource("sh/harold/fulcrum/velocity/api/rank/VelocityRankUtils.java");

        assertTrue(rankUtils.contains("rankReader.quoteRanks"));
        assertTrue(rankUtils.contains("failed closed"));
        assertFalse(rankUtils.contains("rankReader.findRanks"));
    }

    private String readSource(String relativePath) throws IOException {
        return Files.readString(MAIN_SOURCES.resolve(relativePath));
    }
}
