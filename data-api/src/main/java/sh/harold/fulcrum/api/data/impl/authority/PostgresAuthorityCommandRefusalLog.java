package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Records authority command refusals that happen before a command frame can be submitted.
 */
public final class PostgresAuthorityCommandRefusalLog {
    private static final List<String> REQUIRED_TABLES = List.of("authority_command_refusal_log");
    private static final String PROVIDER_KIND = "message-bus-provider";
    private static final String NOT_REPLAYABLE = "NOT_REPLAYABLE";

    private final PostgresConnectionAdapter connectionAdapter;
    private final Gson gson = new Gson();

    /**
     * Creates a Postgres-backed command refusal log.
     *
     * @param connectionAdapter Postgres connection adapter
     */
    public PostgresAuthorityCommandRefusalLog(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
    }

    /**
     * Verifies that command refusal tables exist.
     */
    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required authority command refusal table '" + table
                                + "'. Run data-api migrations before enabling command refusal logging."
                        );
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority command refusal schema", exception);
        }
    }

    /**
     * Records a message-bus command refusal that occurred before authority submission.
     *
     * @param originNode transport sender node
     * @param targetNode authority target node
     * @param wire raw message-bus command payload
     * @param result deterministic refusal result returned to the caller
     */
    public void recordMessageBusRefusal(
        String originNode,
        String targetNode,
        Map<String, Object> wire,
        DataAuthority.CommandResult result
    ) {
        Objects.requireNonNull(result, "result");
        Map<String, Object> commandWire = wire == null ? Map.of() : wire;
        RefusalFrame frame = refusalFrame(originNode, targetNode, commandWire, result);
        AuthorityCommandGuardEvidence.GuardEvidence guardEvidence =
            AuthorityCommandGuardEvidence.preSubmitRefusal(
                originNode,
                targetNode,
                commandWire,
                result,
                frame.payloadHash()
            );

        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_command_refusal_log (
                     refusal_id, command_id, command_type, aggregate_scope, idempotency_key,
                     claimed_actor, verified_principal,
                     origin_node, authority_route, provider_kind, contract_version,
                     expected_contract_fingerprint, received_contract_fingerprint,
                     rejection_reason, result_revision, result_message, replay_eligibility,
                     manifest_payload, command_payload, result_payload,
                     payload_hash, refusal_fingerprint,
                     guard_evidence, guard_evidence_fingerprint,
                     created_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                     ?::jsonb, ?::jsonb, ?::jsonb, ?, ?,
                     ?::jsonb, ?,
                     CURRENT_TIMESTAMP)
                 """)) {
            statement.setObject(1, frame.refusalId());
            statement.setObject(2, result.commandId());
            statement.setString(3, frame.declarationId());
            statement.setString(4, frame.aggregateScope());
            statement.setString(5, frame.idempotencyKey());
            statement.setString(6, frame.claimedActor());
            statement.setString(7, frame.verifiedPrincipal());
            statement.setString(8, frame.originNode());
            statement.setString(9, frame.authorityRoute());
            statement.setString(10, PROVIDER_KIND);
            statement.setInt(11, frame.contractVersion());
            statement.setString(12, frame.expectedContractFingerprint());
            statement.setString(13, frame.receivedContractFingerprint());
            statement.setString(14, result.rejectionReason().name());
            statement.setLong(15, result.revision());
            statement.setString(16, truncate(result.message(), 2000));
            statement.setString(17, NOT_REPLAYABLE);
            statement.setString(18, gson.toJson(frame.manifestPayload()));
            statement.setString(19, gson.toJson(frame.commandPayload()));
            statement.setString(20, gson.toJson(result.settlement().payload()));
            statement.setString(21, frame.payloadHash());
            statement.setString(22, frame.refusalFingerprint());
            statement.setString(23, gson.toJson(guardEvidence.payload()));
            statement.setString(24, guardEvidence.fingerprint());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command refusal " + result.commandId(), exception);
        }
    }

    private RefusalFrame refusalFrame(
        String rawOriginNode,
        String rawTargetNode,
        Map<String, Object> wire,
        DataAuthority.CommandResult result
    ) {
        String originNode = firstKnown(rawOriginNode, "unknown");
        String targetNode = firstKnown(rawTargetNode, "authority");
        String verifiedPrincipal = AuthorityPrincipals.nodePrincipal(originNode);
        String expectedContractFingerprint = DataAuthorityCommandContracts.fingerprint();
        String receivedContractFingerprint = firstKnown(string(wire.get("contractFingerprint")), "missing");
        String expectedRouteManifestFingerprint = DataAuthorityCommandContracts.routeManifestFingerprint();
        String receivedRouteManifestFingerprint = firstKnown(string(wire.get("routeManifestFingerprint")), "missing");
        int contractVersion = intValue(wire.get("schemaVersion"), DataAuthority.COMMAND_SCHEMA_VERSION);
        Map<String, Object> commandPayload = stringObjectMap(mapValue(wire.get("payload")));
        String declarationId = firstKnown(string(wire.get("declarationId")), "unknown");
        String effectiveDeclarationId = declarationId(declarationId);
        String payloadHash = AuthorityCommandFingerprints.hash(
            AuthorityCommandFingerprints.canonicalJson(commandPayload)
        );

        Map<String, Object> manifestPayload = new LinkedHashMap<>();
        manifestPayload.put("commandId", result.commandId().toString());
        manifestPayload.put("declarationId", declarationId);
        manifestPayload.put("actorId", firstKnown(string(wire.get("actorId")), "unknown"));
        manifestPayload.put("scope", firstKnown(string(wire.get("scope")), "unknown"));
        manifestPayload.put("idempotencyKey", firstKnown(string(wire.get("idempotencyKey")), "unknown"));
        manifestPayload.put("deadlineEpochMillis", longValue(wire.get("deadlineEpochMillis"), 0L));
        manifestPayload.put("fencingToken", firstKnown(string(wire.get("fencingToken")), "none"));
        manifestPayload.put("expectedRevision", longValue(wire.get("expectedRevision"), DataAuthority.ANY_REVISION));
        manifestPayload.put("schemaVersion", contractVersion);
        manifestPayload.put("contractFingerprint", receivedContractFingerprint);
        manifestPayload.put("expectedContractFingerprint", expectedContractFingerprint);
        manifestPayload.put("routeManifestFingerprint", receivedRouteManifestFingerprint);
        manifestPayload.put("expectedRouteManifestFingerprint", expectedRouteManifestFingerprint);
        manifestPayload.put("route", stringObjectMap(mapValue(wire.get("route"))));
        manifestPayload.put("provenance", stringObjectMap(mapValue(wire.get("provenance"))));
        manifestPayload.put("originNode", originNode);
        manifestPayload.put("targetNode", targetNode);
        manifestPayload.put("verifiedPrincipal", verifiedPrincipal);

        Map<String, Object> fingerprintMaterial = new LinkedHashMap<>();
        fingerprintMaterial.put("preSubmitRefusal", true);
        fingerprintMaterial.put("commandId", result.commandId().toString());
        fingerprintMaterial.put("declarationId", manifestPayload.get("declarationId"));
        fingerprintMaterial.put("actorId", manifestPayload.get("actorId"));
        fingerprintMaterial.put("verifiedPrincipal", verifiedPrincipal);
        fingerprintMaterial.put("scope", manifestPayload.get("scope"));
        fingerprintMaterial.put("expectedRevision", manifestPayload.get("expectedRevision"));
        fingerprintMaterial.put("schemaVersion", contractVersion);
        fingerprintMaterial.put("expectedContractFingerprint", expectedContractFingerprint);
        fingerprintMaterial.put("receivedContractFingerprint", receivedContractFingerprint);
        fingerprintMaterial.put("expectedRouteManifestFingerprint", expectedRouteManifestFingerprint);
        fingerprintMaterial.put("receivedRouteManifestFingerprint", receivedRouteManifestFingerprint);
        fingerprintMaterial.put("route", manifestPayload.get("route"));
        fingerprintMaterial.put("rejectionReason", result.rejectionReason().name());
        fingerprintMaterial.put("payloadHash", payloadHash);

        return new RefusalFrame(
            UUID.randomUUID(),
            effectiveDeclarationId,
            string(manifestPayload.get("scope")),
            string(manifestPayload.get("idempotencyKey")),
            string(manifestPayload.get("actorId")),
            verifiedPrincipal,
            originNode,
            "messagebus:" + originNode + "->" + targetNode,
            contractVersion,
            expectedContractFingerprint,
            receivedContractFingerprint,
            manifestPayload,
            commandPayload,
            payloadHash,
            AuthorityCommandFingerprints.hash(AuthorityCommandFingerprints.canonicalJson(fingerprintMaterial))
        );
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(key.toString(), value);
            }
        });
        return result;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstKnown(String value, String fallback) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value) ? fallback : value;
    }

    private static String declarationId(String declarationId) {
        try {
            return DataAuthorityCommandContracts.contractByDeclarationId(declarationId).declarationId();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record RefusalFrame(
        UUID refusalId,
        String declarationId,
        String aggregateScope,
        String idempotencyKey,
        String claimedActor,
        String verifiedPrincipal,
        String originNode,
        String authorityRoute,
        int contractVersion,
        String expectedContractFingerprint,
        String receivedContractFingerprint,
        Map<String, Object> manifestPayload,
        Map<String, Object> commandPayload,
        String payloadHash,
        String refusalFingerprint
    ) {
    }
}
