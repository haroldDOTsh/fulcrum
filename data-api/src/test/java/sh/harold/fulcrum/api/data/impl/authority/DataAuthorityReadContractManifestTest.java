package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataAuthorityReadContractManifestTest {
    private static final Map<DataAuthorityReadContracts.ReadType, DataAuthorityReadContracts.ReadContract> CONTRACTS =
        DataAuthorityReadContracts.all();

    @Test
    void readContractManifestCoversEveryReadType() {
        assertThat(CONTRACTS.keySet()).containsExactlyInAnyOrderElementsOf(
            EnumSet.allOf(DataAuthorityReadContracts.ReadType.class)
        );
        for (DataAuthorityReadContracts.ReadContract contract : CONTRACTS.values()) {
            assertThat(contract.allowedFields())
                .as(contract.type() + " allowed fields")
                .containsAll(contract.requiredFields());
            assertThat(contract.projectionFamily()).isNotBlank();
            assertThat(contract.servingStore()).isNotBlank();
            assertThat(contract.cacheStore()).isEqualTo("valkey");
            assertThat(contract.minimumRevisionFloor()).isNotNegative();
            assertThat(contract.defaultCacheMaxAgeMillis()).isEqualTo(
                DataAuthorityReadContracts.DEFAULT_CACHE_MAX_AGE_MILLIS
            );
        }
        assertThat(CONTRACTS.get(DataAuthorityReadContracts.ReadType.PLAYER_PROFILE).servingStore())
            .isEqualTo("postgresql-read-replica");
        assertThat(CONTRACTS.get(DataAuthorityReadContracts.ReadType.PLAYER_PRESENCE).servingStore())
            .isEqualTo("cassandra");
        assertThat(CONTRACTS.get(DataAuthorityReadContracts.ReadType.PLAYER_RANK).servingStore())
            .isEqualTo("cassandra");
    }

    @Test
    void readContractManifestFingerprintIsStable() throws Exception {
        String actual = DataAuthorityReadContracts.fingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-read-contract.sha256"));
    }

    @Test
    void readContractPayloadCarriesSchemaAndFingerprint() {
        DataAuthority.ReadVisibilityToken visibilityToken = new DataAuthority.ReadVisibilityToken(
            "rank:player:test",
            "state.rank",
            "rank:player:test",
            12,
            4L,
            5L,
            "1".repeat(64)
        );
        Map<String, Object> payload = DataAuthorityReadContracts.payload(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            Map.of(
                "playerId", UUID.randomUUID().toString(),
                "minimumRevision", 3L,
                "maxAgeMillis", 250L,
                "visibilityToken", visibilityToken.payload()
            )
        );

        assertThat(payload).containsEntry("readType", DataAuthorityReadContracts.ReadType.PLAYER_RANK.name());
        assertThat(payload).containsEntry("schemaVersion", DataAuthorityReadContracts.schemaVersion());
        assertThat(payload).containsEntry("contractFingerprint", DataAuthorityReadContracts.fingerprint());
        assertThat(payload).containsEntry("visibilityToken", visibilityToken.payload());
        assertThat(DataAuthorityReadContracts.rejection(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            payload
        )).isNull();
    }

    @Test
    void readContractAppliesRevisionFloorWithoutChangingFreshnessRequest() {
        DataAuthority.ReadRequirement requirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            DataAuthority.ReadRequirement.freshAtLeast(3L, 250L)
        );

        assertThat(requirement.minimumRevision()).isEqualTo(3L);
        assertThat(requirement.maxAgeMillis()).isEqualTo(250L);
        assertThat(DataAuthorityReadContracts.defaultCacheMaxAgeMillis())
            .isEqualTo(DataAuthorityReadContracts.DEFAULT_CACHE_MAX_AGE_MILLIS);
    }

    @Test
    void readContractPreservesVisibilityToken() {
        DataAuthority.ReadVisibilityToken visibilityToken = new DataAuthority.ReadVisibilityToken(
            "rank:player:test",
            "state.rank",
            "rank:player:test",
            12,
            4L,
            5L,
            "1".repeat(64)
        );
        DataAuthority.ReadRequirement requirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            DataAuthority.ReadRequirement.after(visibilityToken)
        );

        assertThat(requirement.minimumRevision()).isEqualTo(5L);
        assertThat(requirement.visibilityToken()).isEqualTo(visibilityToken);
    }

    @Test
    void readContractRejectsUnknownPayloadField() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(DataAuthorityReadContracts.payload(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            Map.of(
                "playerId", UUID.randomUUID().toString(),
                "minimumRevision", 0L,
                "maxAgeMillis", -1L
            )
        ));
        payload.put("legacyCollection", "player_profiles");

        assertThat(DataAuthorityReadContracts.rejection(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            payload
        )).contains("legacyCollection");
    }

    private static String goldenFingerprint(String path) throws Exception {
        try (InputStream input = DataAuthorityReadContractManifestTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("Missing golden contract fingerprint resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
