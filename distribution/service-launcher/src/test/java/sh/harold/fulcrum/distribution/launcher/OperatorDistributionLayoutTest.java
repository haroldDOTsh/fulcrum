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
        assertTrue(powershell.contains("docker run @dockerArgs"));
        assertTrue(powershell.contains("\"--rm\""));
        assertTrue(shell.contains("ghcr.io/harolddotsh/fulcrum-service-launcher:5.0.0-alpha.1"));
        assertTrue(powershell.contains("ghcr.io/harolddotsh/fulcrum-service-launcher:5.0.0-alpha.1"));
        assertTrue(shell.contains("/var/run/docker.sock"));
        assertTrue(powershell.contains("FULCRUM_DOCKER_SOCKET"));
        assertTrue(shell.contains("DOCKER_HOST"));
        assertTrue(powershell.contains("DOCKER_HOST"));
        assertTrue(shell.contains("FULCRUM_KUBE_DIR"));
        assertTrue(powershell.contains("FULCRUM_KUBE_DIR"));
        assertTrue(shell.contains("/root/.kube"));
        assertTrue(powershell.contains("/root/.kube"));
        assertFalse(combined.contains("gradle"));
        assertFalse(combined.contains(".java"));
    }

    @Test
    void serviceLauncherImageIncludesComposeRuntimeClient() throws IOException {
        String dockerfile = resource("fulcrum/container/service-launcher/Dockerfile");

        assertTrue(dockerfile.contains("docker.io"));
        assertTrue(dockerfile.contains("docker-compose-v2"));
        assertTrue(dockerfile.contains("get.helm.sh"));
        assertTrue(dockerfile.contains("/usr/local/bin/helm"));
        assertTrue(dockerfile.contains("/usr/local/bin/kubectl"));
        assertTrue(dockerfile.contains("/usr/local/bin/k3d"));
        assertTrue(dockerfile.contains("/usr/local/bin/kind"));
    }

    @Test
    void operatorPackageResourcesIncludeComposeAndHelmUnits() throws IOException {
        assertTrue(resource("fulcrum/operator/install.sh").contains("Docker or a compatible container runtime"));
        assertTrue(resource("fulcrum/operator/install.ps1").contains("Docker or a compatible container runtime"));
        assertTrue(resource("fulcrum/operator/README.md").contains("fulcrum up --tier full-engine"));
        assertTrue(resource("fulcrum/operator/README.md").contains("fulcrum up --profile small-production"));
        assertTrue(resource("fulcrum/operator/README.md").contains("fulcrum cluster up|status|down"));
        assertTrue(resource("fulcrum/operator/README.md").contains("fulcrum dev test --shape=in-memory|local-cluster"));
        assertTrue(resource("fulcrum/operator/README.md").contains("fulcrum author publish --project=<path>"));
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
