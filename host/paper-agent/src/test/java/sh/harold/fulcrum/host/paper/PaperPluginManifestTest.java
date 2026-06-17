package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperPluginManifestTest {
    @Test
    void pluginYmlDeclaresPaperEntrypointAndApiVersion() throws IOException {
        try (var stream = PaperPluginManifestTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            if (stream == null) {
                throw new IOException("Missing plugin.yml");
            }
            String pluginYml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(pluginYml.contains("name: FulcrumPaperAgent"));
            assertTrue(pluginYml.contains("main: sh.harold.fulcrum.host.paper.FulcrumPaperPlugin"));
            assertTrue(pluginYml.contains("api-version: '26.1.2'"));
            assertTrue(pluginYml.contains("load: POSTWORLD"));
        }
    }
}
