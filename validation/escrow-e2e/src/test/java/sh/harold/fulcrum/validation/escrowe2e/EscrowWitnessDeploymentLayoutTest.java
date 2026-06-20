package sh.harold.fulcrum.validation.escrowe2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EscrowWitnessDeploymentLayoutTest {
    @Test
    void witnessImageAndJobAreValidationOwned() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));
        String dockerfile = resource("fulcrum/container/escrow-witness/Dockerfile");
        String manifest = resource("fulcrum/kubernetes/escrow-e2e/escrow-witness.yaml");

        assertTrue(build.contains("applicationName = \"escrow-e2e-witness\""));
        assertTrue(build.contains("HeadlessAuctionBotWitnessMain"));
        assertTrue(build.contains("escrowWitnessImageContext"));
        assertTrue(build.contains("escrowWitnessImage"));
        assertTrue(build.contains("escrowWitnessRenderManifests"));
        assertTrue(build.contains("escrowWitnessClusterImportImage"));
        assertTrue(build.contains("escrowWitnessClusterApply"));
        assertTrue(build.contains("escrowWitnessClusterRun"));
        assertTrue(build.contains("fulcrum.escrowWitnessImage"));
        assertTrue(build.contains("fulcrum.escrowWitnessWaitTimeout"));
        assertTrue(build.contains("k3d\", \"image\", \"import\""));
        assertTrue(build.contains("kind\", \"load\", \"docker-image\""));
        assertTrue(build.contains("job/fulcrum-escrow-e2e-witness"));

        assertTrue(dockerfile.contains("FROM eclipse-temurin:26-jre"));
        assertTrue(dockerfile.contains("COPY escrow-e2e-witness /opt/fulcrum/escrow-e2e-witness"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/opt/fulcrum/escrow-e2e-witness/bin/escrow-e2e-witness\"]"));

        assertTrue(manifest.contains("kind: ServiceAccount"));
        assertTrue(manifest.contains("kind: Role"));
        assertTrue(manifest.contains("resources: [\"pods\", \"pods/log\"]"));
        assertTrue(manifest.contains("verbs: [\"delete\"]"));
        assertTrue(manifest.contains("kind: Job"));
        assertTrue(manifest.contains("image: ghcr.io/sh-harold/fulcrum-escrow-e2e-witness:dev"));
        assertTrue(manifest.contains("FULCRUM_WITNESS_MODE"));
        assertTrue(manifest.contains("value: \"live-store\""));
        assertTrue(manifest.contains("FULCRUM_WITNESS_DELETE_ESCROW_POD"));
        assertTrue(manifest.contains("value: \"true\""));
        assertTrue(manifest.contains("FULCRUM_WITNESS_LIVE_TIMEOUT_SECONDS"));
        assertTrue(manifest.contains("FULCRUM_WITNESS_KUBERNETES_TIMEOUT_SECONDS"));
        assertTrue(manifest.contains("FULCRUM_WITNESS_PROOF_FILE"));
        assertTrue(manifest.contains("FULCRUM_ESCROW_POD_SELECTOR"));
        assertTrue(manifest.contains("envFrom:"));
        assertTrue(manifest.contains("name: fulcrum-auction-escrow-config"));
        assertTrue(manifest.contains("wait-for-kafka"));
        assertTrue(manifest.contains("wait-for-postgres"));
        assertTrue(manifest.contains("wait-for-cassandra"));
        assertTrue(manifest.contains("wait-for-valkey"));
        assertTrue(manifest.contains("FULCRUM_POSTGRES_USERNAME"));
        assertTrue(manifest.contains("FULCRUM_POSTGRES_PASSWORD"));
        assertTrue(manifest.contains("name: fulcrum-postgres-credentials"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = EscrowWitnessDeploymentLayoutTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
