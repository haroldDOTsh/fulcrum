package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperatorDistributionLayoutTest {
    @Test
    void operatorLaunchersUsePublishedImageAndContainerRuntimeOnly() throws IOException {
        String shell = resource("fulcrum/operator/bin/fulcrum");
        String powershell = resource("fulcrum/operator/bin/fulcrum.ps1");
        String combined = (shell + powershell).toLowerCase(Locale.ROOT);

        assertTrue(shell.contains("docker run --rm"));
        assertTrue(powershell.contains("docker run --rm"));
        assertTrue(shell.contains("ghcr.io/sh-harold/fulcrum-service-launcher:0.1.0-SNAPSHOT"));
        assertTrue(powershell.contains("ghcr.io/sh-harold/fulcrum-service-launcher:0.1.0-SNAPSHOT"));
        assertFalse(combined.contains("gradle"));
        assertFalse(combined.contains(".java"));
    }

    @Test
    void operatorPackageResourcesIncludeComposeAndHelmUnits() throws IOException {
        assertTrue(resource("fulcrum/operator/install.sh").contains("Docker or a compatible container runtime"));
        assertTrue(resource("fulcrum/operator/install.ps1").contains("Docker or a compatible container runtime"));
        assertTrue(resource("fulcrum/compose/single-machine-full-engine.compose.yaml").contains("name: fulcrum-single-machine"));
        assertTrue(resource("fulcrum/helm/fulcrum/Chart.yaml").contains("name: fulcrum"));
        assertTrue(resource("fulcrum/helm/fulcrum/values-small-production.yaml").contains("profile: small-production"));
        assertTrue(resource("fulcrum/helm/fulcrum/values-large-production.yaml").contains("profile: large-production"));
    }

    private static String resource(String path) throws IOException {
        try (var input = OperatorDistributionLayoutTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
