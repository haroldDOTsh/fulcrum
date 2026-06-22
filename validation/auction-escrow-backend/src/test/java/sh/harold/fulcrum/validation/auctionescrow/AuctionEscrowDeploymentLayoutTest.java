package sh.harold.fulcrum.validation.auctionescrow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowDeploymentLayoutTest {
    @Test
    void imageContextInstallsExecutableEscrowBackendDistribution() throws IOException {
        String dockerfile = resource("fulcrum/container/auction-escrow/Dockerfile");
        String readme = resource("fulcrum/container/auction-escrow/README.txt");
        String build = Files.readString(Path.of("build.gradle.kts")).replace("\r\n", "\n");

        assertTrue(dockerfile.contains("FROM eclipse-temurin:26-jre"));
        assertTrue(dockerfile.contains("COPY auction-escrow-backend /opt/fulcrum/auction-escrow"));
        assertTrue(dockerfile.contains("chmod +x /opt/fulcrum/auction-escrow/bin/auction-escrow-backend"));
        assertTrue(dockerfile.contains("mkdir -p /var/run/fulcrum"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/opt/fulcrum/auction-escrow/bin/auction-escrow-backend\"]"));

        assertTrue(build.contains("applicationName = \"auction-escrow-backend\""));
        assertTrue(build.contains("AuctionEscrowBackendMain"));
        assertTrue(build.contains("val defaultAuctionEscrowImage = \"ghcr.io/harolddotsh/fulcrum-auction-escrow:dev\""));
        assertTrue(build.contains("auctionEscrowPublishedImageTag"));
        assertTrue(build.contains("auctionEscrowImageContext"));
        assertTrue(build.contains("auctionEscrowImage"));
        assertTrue(build.contains("auctionEscrowImagePush"));
        assertTrue(build.contains("auctionEscrowImageSign"));
        assertTrue(build.contains("auctionEscrowImagePin"));
        assertTrue(build.contains("publishAuctionEscrowImage"));
        assertTrue(build.contains("signAuctionEscrowImage"));
        assertTrue(build.contains("fulcrum.image-publish-receipt/v1"));
        assertTrue(build.contains("auctionEscrowRenderManifests"));
        assertTrue(build.contains("fulcrum.auctionEscrowImage"));
        assertTrue(build.contains("fulcrum.auctionEscrowPublishedImage"));
        assertTrue(build.contains("oras\", \"resolve\""));
        assertTrue(build.contains("cosign\", \"sign\", \"--yes\""));
        assertTrue(build.contains("auctionEscrowClusterImportImage"));
        assertTrue(build.contains("auctionEscrowClusterApply"));
        assertTrue(build.contains("auctionEscrowClusterWaitForInitialized"));
        assertTrue(build.contains("auctionEscrowClusterWaitForReady"));
        assertTrue(build.contains("auctionEscrowClusterRestartProof"));
        assertTrue(build.contains("auctionEscrowClusterStatus"));
        assertTrue(build.contains("auctionEscrowClusterDeploy"));
        assertTrue(build.contains("distribution/service-launcher/build/cluster-e2e/kubeconfig.yaml"));
        assertTrue(build.contains("k3d\", \"image\", \"import\""));
        assertTrue(build.contains("kind\", \"load\", \"docker-image\""));
        assertTrue(build.contains("--for=condition=Initialized"));
        assertTrue(build.contains("--for=condition=Ready"));
        assertTrue(build.contains("\"delete\",\n            \"pod\""));
        assertTrue(build.contains("auction-escrow-restart-proof.txt"));

        assertTrue(readme.contains("auctionEscrowImageContext"));
        assertTrue(readme.contains("auctionEscrowImage"));
        assertTrue(readme.contains("fulcrum.auctionEscrowImage"));
        assertTrue(readme.contains("FULCRUM_ESCROW_READY_FILE"));
        assertTrue(readme.contains("Kafka/PostgreSQL/Cassandra/Valkey store clients"));
        assertTrue(readme.contains("boot command"));
        assertTrue(readme.contains("guarded store-backed worker"));
    }

    @Test
    void kubernetesManifestDeclaresSingleWriterEscrowDeploymentWithIdentityAndStores() throws IOException {
        String manifest = resource("fulcrum/kubernetes/auction-escrow/auction-escrow.yaml");

        assertTrue(manifest.contains("kind: ServiceAccount"));
        assertTrue(manifest.contains("name: fulcrum-auction-escrow"));
        assertTrue(manifest.contains("kind: Secret"));
        assertTrue(manifest.contains("name: fulcrum-auction-escrow-identity"));
        assertTrue(manifest.contains("FULCRUM_CREDENTIAL_REF"));
        assertTrue(manifest.contains("FULCRUM_ESCROW_BUNDLE_DIGEST"));
        assertTrue(manifest.contains("kind: ConfigMap"));
        assertTrue(manifest.contains("FULCRUM_PRINCIPAL_ID: \"principal-auction-escrow-backend\""));
        assertTrue(manifest.contains("FULCRUM_AUTHORITY_DOMAIN: \"auction-escrow\""));
        assertTrue(manifest.contains("FULCRUM_AUTHORITY_RESOURCE_CLASS: \"external-authority\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_CONTRACT_NAME: \"auction.escrow.v1\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_COMMAND_TOPIC: \"cmd.auction.escrow\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_EVENT_TOPIC: \"evt.auction.escrow\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_STATE_TOPIC: \"state.auction.escrow\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_RESPONSE_TOPIC: \"rsp.auction.escrow\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_CONSUMER_GROUP: \"auction-escrow-backend\""));
        assertTrue(manifest.contains("FULCRUM_ESCROW_REPLAY_WATERMARK: \"0\""));
        assertTrue(manifest.contains("FULCRUM_REGISTRATION_ENDPOINT: \"http://fulcrum-authority-registration:18085/authority-backends/register\""));
        assertTrue(manifest.contains("FULCRUM_KAFKA_BOOTSTRAP_SERVERS: \"fulcrum-kafka:9092\""));
        assertTrue(manifest.contains("FULCRUM_POSTGRES_JDBC_URL: \"jdbc:postgresql://fulcrum-postgres:5432/fulcrum\""));
        assertTrue(manifest.contains("FULCRUM_CASSANDRA_CONTACT_POINTS: \"fulcrum-cassandra:9042\""));
        assertTrue(manifest.contains("FULCRUM_VALKEY_ENDPOINT: \"fulcrum-valkey:6379\""));

        assertTrue(manifest.contains("kind: Deployment"));
        assertTrue(manifest.contains("replicas: 1"));
        assertTrue(manifest.contains("type: Recreate"));
        assertFalse(manifest.contains("rollingUpdate"));
        assertFalse(manifest.contains("maxSurge"));
        assertTrue(manifest.contains("sh.harold.fulcrum/writer-authority: \"single\""));
        assertTrue(manifest.contains("image: ghcr.io/harolddotsh/fulcrum-auction-escrow:dev"));
        assertTrue(manifest.contains("fieldPath: metadata.name"));
        assertTrue(manifest.contains("fieldPath: spec.nodeName"));
        assertTrue(manifest.contains("name: fulcrum-postgres-credentials"));
        assertTrue(manifest.contains("key: POSTGRES_USER"));
        assertTrue(manifest.contains("key: POSTGRES_PASSWORD"));
        assertTrue(manifest.contains("wait-for-kafka"));
        assertTrue(manifest.contains("wait-for-postgres"));
        assertTrue(manifest.contains("wait-for-cassandra"));
        assertTrue(manifest.contains("wait-for-valkey"));
        assertTrue(manifest.contains("wait-for-authority-registration"));
        assertTrue(manifest.contains("until nc -z fulcrum-authority-registration 18085"));
        assertTrue(manifest.contains("readinessProbe:"));
        assertTrue(manifest.contains("test -f /var/run/fulcrum/auction-escrow.ready"));
        assertTrue(manifest.contains("livenessProbe:"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = AuctionEscrowDeploymentLayoutTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
