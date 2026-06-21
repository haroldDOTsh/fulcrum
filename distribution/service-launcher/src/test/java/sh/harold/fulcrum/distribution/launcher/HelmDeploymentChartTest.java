package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HelmDeploymentChartTest {
    @Test
    void chartShipsSmallAndLargeProductionValuePresets() throws IOException {
        String chart = resource("fulcrum/helm/fulcrum/Chart.yaml");
        String small = resource("fulcrum/helm/fulcrum/values-small-production.yaml");
        String large = resource("fulcrum/helm/fulcrum/values-large-production.yaml");

        assertTrue(chart.contains("name: fulcrum"));
        assertTrue(small.contains("profile: small-production"));
        assertTrue(small.contains("placement: co-located-service-families"));
        assertTrue(small.contains("redundancy: reduced"));
        assertTrue(large.contains("profile: large-production"));
        assertTrue(large.contains("placement: separated-service-families"));
        assertTrue(large.contains("redundancy: full"));
        assertTrue(small.contains("objectStorage: external-object-store"));
        assertTrue(large.contains("objectStorage: external-object-store"));
    }

    @Test
    void chartTemplatesUsePublishedImagesAndNoBuildSurface() throws IOException {
        String values = resource("fulcrum/helm/fulcrum/values.yaml");
        String roles = resource("fulcrum/helm/fulcrum/templates/roles.yaml");
        String stores = resource("fulcrum/helm/fulcrum/templates/stores.yaml");
        String combined = values + roles + stores;

        assertTrue(values.contains("ghcr.io/sh-harold/fulcrum-service-launcher:0.1.0-SNAPSHOT"));
        assertTrue(values.contains("ghcr.io/sh-harold/fulcrum-paper-gameserver:0.1.0-SNAPSHOT"));
        assertTrue(values.contains("ghcr.io/sh-harold/fulcrum-velocity-proxy:0.1.0-SNAPSHOT"));
        assertFalse(combined.contains("build:"));
        assertFalse(combined.contains(".java"));
        assertFalse(combined.toLowerCase(java.util.Locale.ROOT).contains("gradle"));
    }

    @Test
    void roleTemplatesDelegateToExistingLauncherEntrypoint() throws IOException {
        String roles = resource("fulcrum/helm/fulcrum/templates/roles.yaml");

        assertRole(roles, "authority-service");
        assertRole(roles, "controller-service");
        assertRole(roles, "worker-agent");
        assertRole(roles, "paper-agent");
        assertRole(roles, "velocity-agent");
        assertTrue(roles.contains("--profile={{ .Values.profile }}"));
        assertFalse(roles.contains("--tier="));
    }

    private static void assertRole(String roles, String role) {
        assertTrue(roles.contains("sh.harold.fulcrum/role: " + role), role);
        assertTrue(roles.contains("--role=" + role), role);
        assertTrue(roles.contains("--mode=run"), role);
    }

    private static String resource(String path) throws IOException {
        try (var input = HelmDeploymentChartTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
