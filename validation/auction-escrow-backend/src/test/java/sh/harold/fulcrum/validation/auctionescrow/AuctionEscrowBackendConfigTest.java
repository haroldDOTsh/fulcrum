package sh.harold.fulcrum.validation.auctionescrow;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowBackendConfigTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Test
    void buildsCredentialedRegistrationRequestFromDeploymentEnvironment() {
        AuctionEscrowBackendConfig config = AuctionEscrowBackendConfig.from(environment());
        AuthorityBackendRegistrationRequest request = config.registrationRequest(NOW);

        assertEquals(AuctionEscrowAuthority.descriptor(), request.descriptor());
        assertEquals("sha256:auction-escrow-backend-dev", request.bundleDigest());
        assertEquals(NOW, request.requestedAt());
        assertTrue(request.securityContext().isPresent());
        assertEquals("principal-auction-escrow-backend",
                request.securityContext().orElseThrow().identity().principalId().value());
        assertTrue(request.securityContext().orElseThrow().credentialScope().permits(
                HostResourceFamily.AUTHORITY_DOMAIN,
                HostAccessMode.PRODUCE,
                AuctionEscrowAuthority.AUTHORITY_DOMAIN));
        assertTrue(request.securityContext().orElseThrow().credentialScope().permits(
                HostResourceFamily.RESOURCE_CLASS,
                HostAccessMode.READ,
                AuctionEscrowAuthority.RESOURCE_CLASS));
        assertEquals(0, config.replayWatermark());
        assertTrue(config.registrationEndpoint().isEmpty());
        assertEquals(AuctionEscrowBackendConfig.StartupMode.SERVE, config.startupMode());
        assertTrue(config.bootSummary().contains("authorityDomain=auction-escrow"));
        assertTrue(config.bootSummary().contains("responseTopic=rsp.auction.escrow"));
        assertTrue(config.bootSummary().contains("consumerGroup=auction-escrow-backend"));
        assertTrue(config.storeBindingFingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void acceptsOptionalRegistrationEndpointForBootstrapHandshake() {
        Map<String, String> environment = environment();
        environment.put("FULCRUM_REGISTRATION_ENDPOINT", "http://fulcrum-control:8080/authority-backends/register");

        AuctionEscrowBackendConfig config = AuctionEscrowBackendConfig.from(environment);

        assertEquals(
                URI.create("http://fulcrum-control:8080/authority-backends/register"),
                config.registrationEndpoint().orElseThrow());
        assertTrue(config.bootSummary().contains("registrationEndpoint=http://fulcrum-control:8080/authority-backends/register"));
    }

    @Test
    void rejectsMissingStoreBindingBeforeBackendCanBoot() {
        Map<String, String> environment = environment();
        environment.remove("FULCRUM_VALKEY_ENDPOINT");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AuctionEscrowBackendConfig.from(environment));

        assertTrue(exception.getMessage().contains("FULCRUM_VALKEY_ENDPOINT"));
    }

    @Test
    void rejectsIdentityForWrongAuthorityDomain() {
        Map<String, String> environment = environment();
        environment.put("FULCRUM_AUTHORITY_DOMAIN", "wrong-domain");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AuctionEscrowBackendConfig.from(environment));

        assertTrue(exception.getMessage().contains("authorityDomain must be auction-escrow"));
    }

    private static Map<String, String> environment() {
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
        environment.put("FULCRUM_ESCROW_REPLAY_WATERMARK", "0");
        environment.put("FULCRUM_ESCROW_READY_FILE", "/var/run/fulcrum/auction-escrow.ready");
        environment.put("FULCRUM_ESCROW_STARTUP_MODE", "serve");
        return environment;
    }
}
