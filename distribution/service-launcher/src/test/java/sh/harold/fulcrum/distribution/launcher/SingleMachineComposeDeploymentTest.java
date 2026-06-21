package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SingleMachineComposeDeploymentTest {
    private static final String RESOURCE = "fulcrum/compose/single-machine-full-engine.compose.yaml";

    @Test
    void composeAssetUsesPublishedImagesAndNoBuildContext() throws IOException {
        String compose = compose();

        assertTrue(compose.contains("image: ${FULCRUM_SERVICE_LAUNCHER_IMAGE:-ghcr.io/sh-harold/fulcrum-service-launcher:0.1.0-SNAPSHOT}"));
        assertTrue(compose.contains("image: ${FULCRUM_PAPER_GAMESERVER_IMAGE:-ghcr.io/sh-harold/fulcrum-paper-gameserver:0.1.0-SNAPSHOT}"));
        assertTrue(compose.contains("image: ${FULCRUM_VELOCITY_PROXY_IMAGE:-ghcr.io/sh-harold/fulcrum-velocity-proxy:0.1.0-SNAPSHOT}"));
        assertFalse(compose.contains("build:"));
        assertFalse(compose.contains(".java"));
        assertFalse(compose.toLowerCase(java.util.Locale.ROOT).contains("gradle"));
    }

    @Test
    void composeAssetContainsFullEngineStoresAndRoleServices() throws IOException {
        String compose = compose();

        assertService(compose, "fulcrum-kafka");
        assertService(compose, "fulcrum-postgres");
        assertService(compose, "fulcrum-cassandra");
        assertService(compose, "fulcrum-valkey");
        assertService(compose, "fulcrum-object-store");
        assertService(compose, "authority-service");
        assertService(compose, "controller-service");
        assertService(compose, "worker-agent");
        assertService(compose, "paper-agent");
        assertService(compose, "velocity-agent");
    }

    @Test
    void composeRoleServicesDelegateToExistingLauncherEntrypoint() throws IOException {
        String compose = compose();

        assertRoleCommand(compose, "authority-service");
        assertRoleCommand(compose, "controller-service");
        assertRoleCommand(compose, "worker-agent");
        assertRoleCommand(compose, "paper-agent");
        assertRoleCommand(compose, "velocity-agent");
    }

    private static void assertService(String compose, String serviceName) {
        assertTrue(compose.contains("  " + serviceName + ":"), serviceName);
    }

    private static void assertRoleCommand(String compose, String role) {
        assertTrue(compose.contains("  " + role + ":"), role);
        assertTrue(compose.contains("- \"--profile=single-machine\""), role);
        assertTrue(compose.contains("- \"--tier=full-engine\""), role);
        assertTrue(compose.contains("- \"--role=" + role + "\""), role);
        assertTrue(compose.contains("- \"--mode=run\""), role);
    }

    private static String compose() throws IOException {
        try (var input = SingleMachineComposeDeploymentTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(input, RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
