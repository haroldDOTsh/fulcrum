package sh.harold.fulcrum.validation.auctionescrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowReadinessEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void publishesReadyFileOnlyAfterAdmittedRegistrationAndBootApplyEvidence() throws IOException {
        Path readyFile = tempDir.resolve("auction-escrow.ready");
        AuctionEscrowBackendConfig config = config(readyFile, 0);
        AuthorityBackendRegistrationReceipt registration = register(config);

        AuctionEscrowReadinessEvidence evidence = AuctionEscrowBootReadiness.prove(
                config,
                registration,
                NOW,
                "boot-nonce-alpha");
        AuctionEscrowReadinessPublisher.publish(readyFile, evidence);

        String document = Files.readString(readyFile);
        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, registration.status());
        assertTrue(document.contains("schema=auction-escrow-readiness/v1"));
        assertTrue(document.contains("status=ready"));
        assertTrue(document.contains("receiptId=" + registration.receiptId()));
        assertTrue(document.contains("fencingEpoch=" + registration.fencingEpoch()));
        assertTrue(document.contains("storeBindingFingerprint=" + config.storeBindingFingerprint()));
        assertTrue(document.contains("bootNonce=boot-nonce-alpha"));
        assertTrue(document.contains("appliedOffsetSource=auction-escrow-boot-probe"));
        assertTrue(document.contains("appliedOffsetPosition=0"));
        assertTrue(document.contains("appliedThrough=1"));
        assertTrue(document.contains("requiredReplayWatermark=0"));
        assertTrue(document.contains("applyCount=1"));
        assertTrue(document.contains("runtimeStatus=ACCEPTED"));
        assertTrue(document.contains("replayed=false"));
        assertTrue(document.contains("evidenceDigest=" + evidence.evidenceDigest()));
        assertFalse(document.contains("fulcrum-dev-password"));
    }

    @Test
    void refusesReadyEvidenceWhenReplayWatermarkIsAheadOfAppliedBootProbe() {
        Path readyFile = tempDir.resolve("auction-escrow.ready");
        AuctionEscrowBackendConfig config = config(readyFile, 2);
        AuthorityBackendRegistrationReceipt registration = register(config);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AuctionEscrowBootReadiness.prove(config, registration, NOW, "boot-nonce-beta"));

        assertTrue(exception.getMessage().contains("replay watermark"));
        assertFalse(Files.exists(readyFile));
    }

    private static AuthorityBackendRegistrationReceipt register(AuctionEscrowBackendConfig config) {
        return new CapabilityBackendRegistrationController().register(config.registrationRequest(NOW));
    }

    private static AuctionEscrowBackendConfig config(Path readyFile, long replayWatermark) {
        Map<String, String> environment = new HashMap<>();
        environment.put("FULCRUM_INSTANCE_ID", "fulcrum-auction-escrow-0");
        environment.put("FULCRUM_INSTANCE_KIND", "authority-backend");
        environment.put("FULCRUM_POOL_ID", "pool-auction-escrow");
        environment.put("FULCRUM_MACHINE_REF", "node-a");
        environment.put("FULCRUM_PRINCIPAL_ID", "principal-auction-escrow-backend");
        environment.put("FULCRUM_CREDENTIAL_REF", "secret://fulcrum-auction-escrow-identity/credential");
        environment.put("FULCRUM_ESCROW_BUNDLE_DIGEST", "sha256:auction-escrow-backend-dev");
        environment.put("FULCRUM_AUTHORITY_DOMAIN", "auction-escrow");
        environment.put("FULCRUM_AUTHORITY_RESOURCE_CLASS", AuctionEscrowAuthority.RESOURCE_CLASS);
        environment.put("FULCRUM_ESCROW_CONTRACT_NAME", AuctionEscrowAuthority.CONTRACT.value());
        environment.put("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "fulcrum-kafka:9092");
        environment.put("FULCRUM_ESCROW_COMMAND_TOPIC", "cmd.auction.escrow");
        environment.put("FULCRUM_ESCROW_EVENT_TOPIC", "evt.auction.escrow");
        environment.put("FULCRUM_ESCROW_STATE_TOPIC", "state.auction.escrow");
        environment.put("FULCRUM_ESCROW_RESPONSE_TOPIC", "rsp.auction.escrow");
        environment.put("FULCRUM_ESCROW_CONSUMER_GROUP", "auction-escrow-backend");
        environment.put("FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://fulcrum-postgres:5432/fulcrum");
        environment.put("FULCRUM_POSTGRES_USERNAME", "fulcrum");
        environment.put("FULCRUM_POSTGRES_PASSWORD", "fulcrum-dev-password");
        environment.put("FULCRUM_CASSANDRA_CONTACT_POINTS", "fulcrum-cassandra:9042");
        environment.put("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1");
        environment.put("FULCRUM_VALKEY_ENDPOINT", "fulcrum-valkey:6379");
        environment.put("FULCRUM_ESCROW_REPLAY_WATERMARK", Long.toString(replayWatermark));
        environment.put("FULCRUM_ESCROW_READY_FILE", readyFile.toString());
        environment.put("FULCRUM_ESCROW_STARTUP_MODE", "serve");
        return AuctionEscrowBackendConfig.from(environment);
    }
}
