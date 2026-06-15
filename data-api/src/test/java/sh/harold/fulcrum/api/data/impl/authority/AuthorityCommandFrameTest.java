package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityCommandFrameTest {
    @Test
    void commandRoundTripsThroughStoredFramePayloads() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                1_800_000_000_000L,
                "8",
                4L,
                new DataAuthority.CommandProvenance(
                    "paper-1",
                    "messagebus:paper-1->authority",
                    "message-bus-provider",
                    1,
                    "node:paper-1"
                )
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );

        AuthorityCommandFrame storedFrame = AuthorityCommandFrame.fromCommand(command);
        assertThat(storedFrame.route().domain()).isEqualTo("rank");
        assertThat(storedFrame.route().commandTopic()).isEqualTo("cmd.rank");
        assertThat(storedFrame.route().partitionKey()).isEqualTo("rank:player:" + playerId);
        assertThat(storedFrame.manifestPayload())
            .containsEntry("declarationId", "GRANT_RANK")
            .containsEntry("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint())
            .doesNotContainKey("commandType");

        DataAuthority.AuthorityCommand restored = AuthorityCommandFrame.fromPayloads(
            storedFrame.manifestPayload(),
            Map.copyOf(storedFrame.payload())
        ).toCommand();

        assertThat(restored.commandId()).isEqualTo(commandId);
        assertThat(restored.type()).isEqualTo(DataAuthority.CommandType.GRANT_RANK);
        assertThat(restored.provenance().originNode()).isEqualTo("paper-1");
        assertThat(restored.provenance().verifiedPrincipal()).isEqualTo("node:paper-1");
        assertThat(AuthorityCommandPayloads.payload(restored)).isEqualTo(AuthorityCommandPayloads.payload(command));
        assertThat(AuthorityCommandFingerprints.fingerprint(restored).commandFingerprint())
            .isEqualTo(AuthorityCommandFingerprints.fingerprint(command).commandFingerprint());
    }

    @Test
    void storedFrameRequiresDeclarationIdInManifestPayload() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                1_800_000_000_000L,
                "8",
                4L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
        AuthorityCommandFrame storedFrame = AuthorityCommandFrame.fromCommand(command);
        Map<String, Object> manifestPayload = new java.util.LinkedHashMap<>(storedFrame.manifestPayload());
        manifestPayload.remove("declarationId");

        assertThatThrownBy(() -> AuthorityCommandFrame.fromPayloads(
            manifestPayload,
            Map.copyOf(storedFrame.payload())
        )).hasMessageContaining("declarationId is required");
    }

    @Test
    void storedFrameRejectsExplicitRouteMetadataDrift() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.REVOKE_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                1_800_000_000_000L,
                "8",
                4L
            ),
            playerId,
            "DEFAULT",
            List.of("DEFAULT")
        );
        AuthorityCommandFrame storedFrame = AuthorityCommandFrame.fromCommand(command);
        Map<String, Object> manifestPayload = new java.util.LinkedHashMap<>(storedFrame.manifestPayload());
        manifestPayload.put("route", Map.of(
            "domain", "legacy_rank",
            "commandTopic", "cmd.legacy_rank",
            "eventTopic", "evt.legacy_rank",
            "stateTopic", "state.legacy_rank",
            "partitionKey", "legacy:" + playerId
        ));

        assertThatThrownBy(() -> AuthorityCommandFrame.fromPayloads(
            manifestPayload,
            Map.copyOf(storedFrame.payload())
        )).hasMessageContaining("route domain");
    }
}
