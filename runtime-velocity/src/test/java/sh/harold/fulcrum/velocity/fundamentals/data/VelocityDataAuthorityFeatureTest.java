package sh.harold.fulcrum.velocity.fundamentals.data;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.guard.GameNodeCapabilityManifest;
import sh.harold.fulcrum.api.data.guard.GameNodeStartupAttestation;
import sh.harold.fulcrum.api.data.guard.GameNodeStorageGuard;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityDataAuthorityFeatureTest {
    @Test
    void localAuthorityIsRejectedOnGameNodes() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            VelocityDataAuthorityFeature::rejectLocalAuthorityMode
        );

        assertTrue(exception.getMessage().contains("authority.mode=local"));
        assertTrue(exception.getMessage().contains("registry-service"));
    }

    @Test
    void remoteAuthorityRejectsDirectStoreConfig() {
        Map<String, Object> config = Map.of(
            "authority", Map.of("mode", "remote"),
            "postgres", Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum",
                "username", "fulcrum"
            )
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> VelocityDataAuthorityFeature.rejectDirectStoreConfigForRemoteAuthority(config)
        );

        assertTrue(exception.getMessage().contains("P3 no-store violation"));
        assertTrue(exception.getMessage().contains("postgres.jdbc-url"));
    }

    @Test
    void bundledNegativeCapabilityManifestMatchesVelocityGameNode() {
        GameNodeCapabilityManifest manifest = GameNodeCapabilityManifest.loadDefault(
            GameNodeStorageGuard.NodeKind.VELOCITY,
            getClass().getClassLoader()
        );

        assertTrue(manifest.forbidLocalAuthority());
        assertTrue(manifest.forbidDirectStoreConfig());
        assertTrue(manifest.forbiddenCapabilities().contains("authority.local"));
        assertTrue(manifest.forbiddenCapabilities().contains("store.direct.sql"));
        assertTrue(manifest.commandContractFingerprint().equals(DataAuthorityCommandContracts.fingerprint()));
        assertTrue(manifest.readContractFingerprint().equals(DataAuthorityReadContracts.fingerprint()));
    }

    @Test
    void negativeCapabilityManifestRejectsLocalAuthorityConfig() {
        Map<String, Object> config = Map.of("authority", Map.of("mode", "local"));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> VelocityDataAuthorityFeature.requireNegativeCapabilityManifest(config)
        );

        assertTrue(exception.getMessage().contains("authority.mode=local"));
    }

    @Test
    void bundledGameNodeConfigDoesNotShipPostgresCredentials() {
        Map<String, Object> config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = new Yaml().load(stream);
        } catch (Exception exception) {
            throw new AssertionError("Failed to load database-config.yml", exception);
        }

        assertFalse(config.containsKey("postgres"));
        VelocityDataAuthorityFeature.rejectDirectStoreConfigForRemoteAuthority(config);
    }

    @Test
    void bundledGameNodeConfigProducesStartupAttestation() {
        Map<String, Object> config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = new Yaml().load(stream);
        } catch (Exception exception) {
            throw new AssertionError("Failed to load database-config.yml", exception);
        }

        GameNodeStartupAttestation.Report report = VelocityDataAuthorityFeature.requireStartupAttestation(config);

        assertTrue(report.passed());
        assertFalse(report.configFingerprint().isBlank());
        assertFalse(report.classpathFingerprint().isBlank());
        assertTrue(report.summary().contains("nodeKind=Velocity"));
        assertTrue(report.summary().contains(DataAuthorityCommandContracts.fingerprint().substring(0, 12)));
        assertTrue(report.summary().contains(DataAuthorityReadContracts.fingerprint().substring(0, 12)));
    }

    @Test
    void startupAttestationConvertsToRuntimeMessageProof() {
        Map<String, Object> config;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("database-config.yml")) {
            assertTrue(stream != null, "database-config.yml resource missing");
            config = new Yaml().load(stream);
        } catch (Exception exception) {
            throw new AssertionError("Failed to load database-config.yml", exception);
        }

        GameNodeStartupAttestation.Report report = VelocityDataAuthorityFeature.requireStartupAttestation(config);
        RuntimeDataAuthorityAttestation attestation = VelocityDataAuthorityFeature.runtimeDataAuthorityAttestation(
            report,
            "remote",
            VelocityDataAuthorityFeature.SNAPSHOT_CACHE_MODE
        );

        assertEquals("Velocity", attestation.getNodeKind());
        assertEquals("remote-authority", attestation.getRuntimeDataMode());
        assertEquals(VelocityDataAuthorityFeature.SNAPSHOT_CACHE_MODE, attestation.getCacheMode());
        assertEquals(report.attestationFingerprint(), attestation.getAttestationFingerprint());
        assertEquals(DataAuthorityCommandContracts.fingerprint(), attestation.getCommandContractFingerprint());
        assertEquals(DataAuthorityReadContracts.fingerprint(), attestation.getReadContractFingerprint());

        RuntimeAuthorityDeliveryManifest deliveryManifest = VelocityDataAuthorityFeature.runtimeAuthorityDeliveryManifest(
            report,
            "remote",
            VelocityDataAuthorityFeature.SNAPSHOT_CACHE_MODE,
            "registry-service"
        );

        assertEquals("Velocity", deliveryManifest.getNodeKind());
        assertEquals("registry-service", deliveryManifest.getAuthorityServerId());
        assertEquals(report.attestationFingerprint(), deliveryManifest.getStartupAttestationFingerprint());
        assertEquals(DataAuthorityCommandContracts.fingerprint(), deliveryManifest.getCommandContractFingerprint());
        assertEquals(
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            deliveryManifest.getCommandRouteManifestFingerprint()
        );
        assertEquals(DataAuthorityReadContracts.fingerprint(), deliveryManifest.getReadContractFingerprint());
        assertEquals("player_rank", deliveryManifest.getCommandDomainsByType().get("GRANT_RANK"));
        assertEquals(
            "rank:player:{aggregateId}=>rank:player:{aggregateId}",
            deliveryManifest.getCommandPartitionKeyVectorsByType().get("GRANT_RANK")
        );
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
