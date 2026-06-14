package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDataAuthorityAttestationSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registrationRequestCarriesDataAuthorityAttestation() throws Exception {
        ServerRegistrationRequest request = new ServerRegistrationRequest("temp-1", "MINI", 15);
        request.setDataAuthorityAttestation(attestation());
        request.setAuthorityDeliveryManifest(deliveryManifest());

        String json = objectMapper.writeValueAsString(request);
        ServerRegistrationRequest restored = objectMapper.readValue(json, ServerRegistrationRequest.class);

        assertNotNull(restored.getDataAuthorityAttestation());
        assertEquals("Paper", restored.getDataAuthorityAttestation().getNodeKind());
        assertEquals("attestation-fingerprint", restored.getDataAuthorityAttestation().getAttestationFingerprint());
        assertTrue(restored.getDataAuthorityAttestation().summary().contains("runtimeDataMode=remote-authority"));
        assertNotNull(restored.getAuthorityDeliveryManifest());
        assertEquals("registry-service", restored.getAuthorityDeliveryManifest().getAuthorityServerId());
        assertEquals("player_rank", restored.getAuthorityDeliveryManifest()
            .getCommandDomainsByType()
            .get("GRANT_RANK"));
        assertEquals("rank:player:{aggregateId}=>rank:player:{aggregateId}", restored.getAuthorityDeliveryManifest()
            .getCommandPartitionKeyVectorsByType()
            .get("GRANT_RANK"));
        assertEquals("kafka", restored.getAuthorityDeliveryManifest()
            .getCommandLogStoresByType()
            .get("GRANT_RANK"));
        assertEquals("cassandra", restored.getAuthorityDeliveryManifest()
            .getCommandHotProjectionStoresByType()
            .get("GRANT_RANK"));
        assertEquals("manifest-fingerprint", restored.getAuthorityDeliveryManifest().getManifestFingerprint());
    }

    @Test
    void heartbeatCarriesDataAuthorityAttestation() throws Exception {
        ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage("mini-1", "MINI");
        heartbeat.setDataAuthorityAttestation(attestation());
        heartbeat.setAuthorityDeliveryManifest(deliveryManifest());

        String json = objectMapper.writeValueAsString(heartbeat);
        ServerHeartbeatMessage restored = objectMapper.readValue(json, ServerHeartbeatMessage.class);

        assertNotNull(restored.getDataAuthorityAttestation());
        assertEquals(1, restored.getDataAuthorityAttestation().getCommandSchemaVersion());
        assertEquals("read-contract-fingerprint", restored.getDataAuthorityAttestation().getReadContractFingerprint());
        assertNotNull(restored.getAuthorityDeliveryManifest());
        assertEquals("PLAYER_RANK", restored.getAuthorityDeliveryManifest()
            .getReadProjectionFamiliesByType()
            .keySet()
            .iterator()
            .next());
        assertEquals("cassandra", restored.getAuthorityDeliveryManifest()
            .getReadServingStoresByType()
            .get("PLAYER_RANK"));
        assertEquals("valkey", restored.getAuthorityDeliveryManifest()
            .getReadCacheStoresByType()
            .get("PLAYER_RANK"));
        assertTrue(restored.getAuthorityDeliveryManifest().summary().contains("authorityServerId=registry-service"));
    }

    @Test
    void oldRegistrationAndHeartbeatMessagesCanOmitAuthorityDeliveryManifest() throws Exception {
        ServerRegistrationRequest registration = objectMapper.readValue(
            "{\"tempId\":\"temp-legacy\",\"serverType\":\"MINI\",\"maxCapacity\":10}",
            ServerRegistrationRequest.class
        );
        ServerHeartbeatMessage heartbeat = objectMapper.readValue(
            "{\"serverId\":\"mini-legacy\",\"serverType\":\"MINI\"}",
            ServerHeartbeatMessage.class
        );

        assertNull(registration.getAuthorityDeliveryManifest());
        assertNull(heartbeat.getAuthorityDeliveryManifest());
    }

    private static RuntimeDataAuthorityAttestation attestation() {
        return new RuntimeDataAuthorityAttestation(
            "Paper",
            1,
            true,
            "remote-authority",
            "watermarked-snapshot-cache",
            1,
            "command-contract-fingerprint",
            1,
            "read-contract-fingerprint",
            "config-fingerprint",
            "classpath-fingerprint",
            "attestation-fingerprint"
        );
    }

    private static RuntimeAuthorityDeliveryManifest deliveryManifest() {
        return new RuntimeAuthorityDeliveryManifest(
            "Paper",
            1,
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint",
            1,
            "command-contract-fingerprint",
            "route-manifest-fingerprint",
            1,
            "read-contract-fingerprint",
            Map.of(
                "GRANT_RANK", "player_rank",
                "RECORD_PLAYER_LOGIN", "player_profile"
            ),
            Map.of(
                "GRANT_RANK", "SYNC_INTERACTIVE",
                "RECORD_PLAYER_LOGIN", "ASYNC_DURABLE"
            ),
            Map.of(
                "GRANT_RANK", "rank:player:{aggregateId}=>rank:player:{aggregateId}",
                "RECORD_PLAYER_LOGIN", "player:{aggregateId}=>player:{aggregateId}"
            ),
            Map.of(
                "GRANT_RANK", "kafka",
                "RECORD_PLAYER_LOGIN", "kafka"
            ),
            Map.of(
                "GRANT_RANK", "cassandra",
                "RECORD_PLAYER_LOGIN", "cassandra"
            ),
            Map.of(
                "GRANT_RANK", "postgresql",
                "RECORD_PLAYER_LOGIN", "postgresql"
            ),
            Map.of(
                "GRANT_RANK", "valkey",
                "RECORD_PLAYER_LOGIN", "valkey"
            ),
            Map.of("PLAYER_RANK", "player_rank"),
            Map.of("PLAYER_RANK", "cassandra"),
            Map.of("PLAYER_RANK", "valkey"),
            "manifest-fingerprint"
        );
    }
}
