package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.guard.GameNodeCapabilityManifest;
import sh.harold.fulcrum.api.data.guard.GameNodeStartupAttestation;
import sh.harold.fulcrum.api.data.guard.GameNodeStorageGuard;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAuthorityFeatureTest {
    @Test
    void localAuthorityIsRejectedOnGameNodes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            DataAuthorityFeature::rejectLocalAuthorityMode
        );

        assertTrue(exception.getMessage().contains("authority.mode=local"));
        assertTrue(exception.getMessage().contains("registry-service"));
    }

    @Test
    void remoteAuthorityRejectsDirectStoreConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("authority.mode", "remote");
        config.set("postgres.jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum");
        config.set("postgres.username", "fulcrum");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> DataAuthorityFeature.rejectDirectStoreConfigForRemoteAuthority(config)
        );

        assertTrue(exception.getMessage().contains("P3 no-store violation"));
        assertTrue(exception.getMessage().contains("postgres.jdbc-url"));
    }

    @Test
    void bundledNegativeCapabilityManifestMatchesPaperGameNode() {
        GameNodeCapabilityManifest manifest = GameNodeCapabilityManifest.loadDefault(
            GameNodeStorageGuard.NodeKind.PAPER,
            getClass().getClassLoader()
        );

        assertTrue(manifest.forbidLocalAuthority());
        assertTrue(manifest.forbidDirectStoreConfig());
        assertTrue(manifest.forbiddenCapabilities().contains("authority.local"));
        assertTrue(manifest.forbiddenCapabilities().contains("authority.command.message-bus"));
        assertTrue(manifest.forbiddenCapabilities().contains("store.direct.sql"));
        assertTrue(manifest.commandContractFingerprint().equals(AuthorityCommandManifest.fingerprint()));
        assertTrue(manifest.readContractFingerprint().equals(DataAuthorityReadContracts.fingerprint()));
    }

    @Test
    void negativeCapabilityManifestRejectsLocalAuthorityConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("authority.mode", "local");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> DataAuthorityFeature.requireNegativeCapabilityManifest(config)
        );

        assertTrue(exception.getMessage().contains("authority.mode=local"));
    }

    @Test
    void negativeCapabilityManifestRejectsMessageBusCommandTransport() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("authority.mode", "remote");
        config.set("authority.command-transport", "message-bus");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> DataAuthorityFeature.requireNegativeCapabilityManifest(config)
        );

        assertTrue(exception.getMessage().contains("authority.command-transport"));
        assertTrue(exception.getMessage().contains("durable Kafka command log"));
    }

    @Test
    void bundledGameNodeConfigDoesNotShipPostgresCredentials() throws Exception {
        YamlConfiguration config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        assertFalse(config.contains("postgres"));
        assertEquals(DataAuthorityFeature.DEFAULT_COMMAND_TRANSPORT, config.getString("authority.command-transport"));
        DataAuthorityFeature.rejectDirectStoreConfigForRemoteAuthority(config);
    }

    @Test
    void bundledGameNodeConfigProducesStartupAttestation() throws Exception {
        YamlConfiguration config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        GameNodeStartupAttestation.Report report = DataAuthorityFeature.requireStartupAttestation(config);

        assertTrue(report.passed());
        assertFalse(report.configFingerprint().isBlank());
        assertFalse(report.classpathFingerprint().isBlank());
        assertTrue(report.summary().contains("nodeKind=Paper"));
        assertTrue(report.summary().contains(AuthorityCommandManifest.fingerprint().substring(0, 12)));
        assertTrue(report.summary().contains(DataAuthorityReadContracts.fingerprint().substring(0, 12)));
    }

    @Test
    void startupAttestationConvertsToRuntimeMessageProof() throws Exception {
        YamlConfiguration config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        GameNodeStartupAttestation.Report report = DataAuthorityFeature.requireStartupAttestation(config);
        RuntimeDataAuthorityAttestation attestation = DataAuthorityFeature.runtimeDataAuthorityAttestation(
            report,
            "remote",
            DataAuthorityFeature.SNAPSHOT_CACHE_MODE
        );

        assertEquals("Paper", attestation.getNodeKind());
        assertEquals("remote-authority", attestation.getRuntimeDataMode());
        assertEquals(DataAuthorityFeature.SNAPSHOT_CACHE_MODE, attestation.getCacheMode());
        assertEquals(report.attestationFingerprint(), attestation.getAttestationFingerprint());
        assertEquals(AuthorityCommandManifest.fingerprint(), attestation.getCommandContractFingerprint());
        assertEquals(DataAuthorityReadContracts.fingerprint(), attestation.getReadContractFingerprint());

        RuntimeAuthorityDeliveryManifest deliveryManifest = DataAuthorityFeature.runtimeAuthorityDeliveryManifest(
            report,
            "remote",
            DataAuthorityFeature.SNAPSHOT_CACHE_MODE,
            "registry-service"
        );

        assertEquals("Paper", deliveryManifest.getNodeKind());
        assertEquals("registry-service", deliveryManifest.getAuthorityServerId());
        assertEquals(report.attestationFingerprint(), deliveryManifest.getStartupAttestationFingerprint());
        assertEquals(AuthorityCommandManifest.fingerprint(), deliveryManifest.getCommandContractFingerprint());
        assertEquals(
            AuthorityCommandManifest.routeManifestFingerprint(),
            deliveryManifest.getCommandRouteManifestFingerprint()
        );
        assertEquals(
            AuthorityDomainTopology.fingerprint(),
            deliveryManifest.getAuthorityDomainTopologyFingerprint()
        );
        assertEquals(DataAuthorityReadContracts.fingerprint(), deliveryManifest.getReadContractFingerprint());
        assertEquals("authority-rank", deliveryManifest.getAuthorityServicesByDomain().get("rank"));
        assertEquals("authority-rank", deliveryManifest.getAuthorityConsumerGroupsByDomain().get("rank"));
        assertEquals("authority-rank", deliveryManifest.getAuthorityPrincipalsByDomain().get("rank"));
        assertEquals("rank", deliveryManifest.getCommandDomainsByType().get("GRANT_RANK"));
        assertEquals(
            "rank:player:{aggregateId}=>rank:player:{aggregateId}",
            deliveryManifest.getCommandPartitionKeyVectorsByType().get("GRANT_RANK")
        );
        assertEquals("authority-rank", deliveryManifest.getCommandAuthorityServicesByType().get("GRANT_RANK"));
        assertEquals("authority-rank", deliveryManifest.getCommandConsumerGroupsByType().get("GRANT_RANK"));
        assertEquals("authority-rank", deliveryManifest.getCommandAuthorityPrincipalsByType().get("GRANT_RANK"));
        assertEquals(
            Integer.toString(AuthorityDomainTopology.domain("rank").partitionCount()),
            deliveryManifest.getCommandPartitionCountsByType().get("GRANT_RANK")
        );
        assertEquals("cmd.rank", deliveryManifest.getCommandTopicsByType().get("GRANT_RANK"));
        assertEquals("rsp.rank", deliveryManifest.getCommandResponseTopicsByType().get("GRANT_RANK"));
        assertEquals("evt.rank", deliveryManifest.getCommandEventTopicsByType().get("GRANT_RANK"));
        assertEquals("state.rank", deliveryManifest.getCommandStateTopicsByType().get("GRANT_RANK"));
        assertEquals("kafka", deliveryManifest.getCommandLogStoresByType().get("GRANT_RANK"));
        assertEquals("cassandra", deliveryManifest.getCommandHotProjectionStoresByType().get("GRANT_RANK"));
        assertEquals("postgresql", deliveryManifest.getCommandHistoryStoresByType().get("GRANT_RANK"));
        assertEquals("valkey", deliveryManifest.getCommandCacheStoresByType().get("GRANT_RANK"));
        assertEquals("player_rank", deliveryManifest.getReadProjectionFamiliesByType().get("PLAYER_RANK"));
        assertEquals("cassandra", deliveryManifest.getReadServingStoresByType().get("PLAYER_RANK"));
        assertEquals("valkey", deliveryManifest.getReadCacheStoresByType().get("PLAYER_RANK"));
        assertFalse(deliveryManifest.getManifestFingerprint().isBlank());
    }
}
