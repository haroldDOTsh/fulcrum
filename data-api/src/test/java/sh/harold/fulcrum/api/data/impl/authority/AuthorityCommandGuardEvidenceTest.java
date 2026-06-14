package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandGuardEvidenceTest {
    @Test
    void terminalEvidenceBindsCommandRouteContractAndResult() {
        DataAuthority.PlayerRankCommand command = rankCommand(UUID.randomUUID(), UUID.randomUUID());
        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            command.commandId(),
            true,
            3L,
            DataAuthority.RejectionReason.NONE,
            "accepted",
            new DataAuthority.CommandSettlement(
                "postgres-data-authority",
                "player_rank",
                "cmd.player_rank",
                "evt.player_rank",
                "state.player_rank",
                command.scope(),
                command.fencingToken(),
                command.idempotencyKey(),
                command.expectedRevision(),
                DataAuthority.SnapshotWatermark.unwatermarked("postgres-data-authority", "player_rank", command.scope(), 3L)
            )
        );

        AuthorityCommandGuardEvidence.GuardEvidence evidence =
            AuthorityCommandGuardEvidence.terminal(command, result, "NOT_REPLAYABLE");

        assertThat(evidence.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(evidence.payload())
            .containsEntry("guardEvidenceVersion", 1)
            .containsEntry("phase", "TERMINAL")
            .containsEntry("commandId", command.commandId().toString())
            .containsEntry("commandType", "GRANT_RANK")
            .containsEntry("aggregateScope", command.scope());
        assertThat(map(evidence.payload().get("contract")))
            .containsEntry("expectedFingerprint", DataAuthorityCommandContracts.fingerprint())
            .containsEntry("receivedFingerprint", DataAuthorityCommandContracts.fingerprint())
            .containsEntry("expectedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint())
            .containsEntry("receivedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint())
            .containsEntry("deliveryMode", DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE.name());
        assertThat(map(evidence.payload().get("route")))
            .containsEntry("commandTopic", "cmd.player_rank")
            .containsEntry("partitionKey", command.scope());
        assertThat(map(evidence.payload().get("writerLane")))
            .containsEntry("domain", "player_rank")
            .containsEntry("partitionKey", command.scope())
            .containsEntry("laneCount", AuthorityCommandLane.DEFAULT_LANE_COUNT);
        assertThat(map(evidence.payload().get("result")))
            .containsEntry("accepted", true)
            .containsEntry("rejectionReason", "NONE")
            .containsEntry("replayEligibility", "NOT_REPLAYABLE");
        assertThat(decisionNames(evidence.payload()))
            .contains(
                "schemaContract",
                "routeManifest",
                "deadline",
                "principal",
                "routeAndScope",
                "writerLane",
                "terminalOutcome"
            );
    }

    @Test
    void preSubmitRefusalEvidenceCapturesContractDriftAndTerminalReason() {
        UUID commandId = UUID.randomUUID();
        UUID scopedPlayerId = UUID.randomUUID();
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("commandId", commandId.toString());
        wire.put("commandType", "GRANT_RANK");
        wire.put("actorId", "rank-service");
        wire.put("scope", "rank:player:" + scopedPlayerId);
        wire.put("idempotencyKey", "rank-refusal:" + commandId);
        wire.put("deadlineEpochMillis", System.currentTimeMillis() + 5000L);
        wire.put("fencingToken", "7");
        wire.put("expectedRevision", DataAuthority.ANY_REVISION);
        wire.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION + 1);
        wire.put("contractFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");
        wire.put("routeManifestFingerprint", "1111111111111111111111111111111111111111111111111111111111111111");
        wire.put("route", Map.of(
            "domain", "player_rank",
            "commandTopic", "cmd.player_rank",
            "eventTopic", "evt.player_rank",
            "stateTopic", "state.player_rank",
            "partitionKey", "rank:player:" + scopedPlayerId
        ));
        wire.put("payload", Map.of("playerId", UUID.randomUUID().toString()));
        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            commandId,
            false,
            DataAuthority.ANY_REVISION,
            DataAuthority.RejectionReason.VALIDATION_FAILED,
            "unsupported schema"
        );

        AuthorityCommandGuardEvidence.GuardEvidence evidence =
            AuthorityCommandGuardEvidence.preSubmitRefusal(
                "paper-1",
                "registry-1",
                wire,
                result,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );

        assertThat(evidence.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(evidence.payload())
            .containsEntry("phase", "PRE_SUBMIT_REFUSAL")
            .containsEntry("verifiedPrincipal", "node:paper-1")
            .containsEntry("targetNode", "registry-1");
        assertThat(map(evidence.payload().get("contract")))
            .containsEntry("expectedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION)
            .containsEntry("receivedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION + 1)
            .containsEntry("receivedFingerprint", "0000000000000000000000000000000000000000000000000000000000000000")
            .containsEntry("expectedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint())
            .containsEntry("receivedRouteManifestFingerprint", "1111111111111111111111111111111111111111111111111111111111111111");
        assertThat(map(evidence.payload().get("route")))
            .containsEntry("partitionKey", "rank:player:" + scopedPlayerId);
        assertThat(map(evidence.payload().get("result")))
            .containsEntry("accepted", false)
            .containsEntry("rejectionReason", "VALIDATION_FAILED")
            .containsEntry("replayEligibility", "NOT_REPLAYABLE");
        assertThat(decision(evidence.payload(), "schemaContract"))
            .containsEntry("verdict", "FAILED");
        assertThat(decision(evidence.payload(), "routeManifest"))
            .containsEntry("verdict", "FAILED");
        assertThat(decision(evidence.payload(), "terminalOutcome"))
            .containsEntry("verdict", "FAILED");
    }

    private static DataAuthority.PlayerRankCommand rankCommand(UUID commandId, UUID playerId) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 5000L,
                "5",
                1L,
                new DataAuthority.CommandProvenance(
                    "paper-1",
                    "messagebus:paper-1->registry-1",
                    "message-bus-provider",
                    DataAuthority.COMMAND_SCHEMA_VERSION,
                    "node:paper-1"
                )
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> decisionNames(Map<String, Object> payload) {
        return decisions(payload).stream()
            .map(decision -> decision.get("guard").toString())
            .toList();
    }

    private static Map<String, Object> decision(Map<String, Object> payload, String guard) {
        return decisions(payload).stream()
            .filter(candidate -> guard.equals(candidate.get("guard")))
            .findFirst()
            .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> decisions(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("decisions");
    }
}
