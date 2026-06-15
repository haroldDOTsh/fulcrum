package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical guard-decision evidence stored beside authority command receipts.
 */
final class AuthorityCommandGuardEvidence {
    private static final int EVIDENCE_VERSION = 1;
    private static final String PASSED = "PASSED";
    private static final String FAILED = "FAILED";
    private static final String OBSERVED = "OBSERVED";
    private static final String SKIPPED = "SKIPPED";

    private AuthorityCommandGuardEvidence() {
    }

    static GuardEvidence received(
        DataAuthority.AuthorityCommand command,
        AuthorityCommandFrame frame,
        AuthorityCommandFingerprints.Fingerprint fingerprint
    ) {
        Map<String, Object> evidence = base("INGRESS_RECEIVED", command, frame, fingerprint);
        evidence.put("decisions", commandGuardDecisions(command, null));
        return of(evidence);
    }

    static GuardEvidence terminal(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        String replayEligibility
    ) {
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(command);
        Map<String, Object> evidence = base(
            "TERMINAL",
            command,
            frame,
            AuthorityCommandFingerprints.fingerprint(command)
        );
        DataAuthority.CommandResult safeResult = Objects.requireNonNull(result, "result");
        evidence.put("result", resultPayload(safeResult, replayEligibility));
        evidence.put("writerClaim", writerClaimPayload(safeResult.settlement().fencingToken()));
        evidence.put("decisions", commandGuardDecisions(command, safeResult));
        return of(evidence);
    }

    static GuardEvidence failure(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        Throwable failure,
        String replayEligibility
    ) {
        GuardEvidence terminal = terminal(command, result, replayEligibility);
        Map<String, Object> evidence = mutableCopy(terminal.payload());
        evidence.put("phase", "FAILED");
        evidence.put("failure", Map.of(
            "type", failure == null ? "unknown" : failure.getClass().getName(),
            "message", failure == null || failure.getMessage() == null ? "" : failure.getMessage()
        ));
        return of(evidence);
    }

    static GuardEvidence preSubmitRefusal(
        String originNode,
        String targetNode,
        Map<String, Object> wire,
        DataAuthority.CommandResult result,
        String payloadHash
    ) {
        Objects.requireNonNull(result, "result");
        Map<String, Object> safeWire = wire == null ? Map.of() : wire;
        String receivedContractFingerprint = firstKnown(string(safeWire.get("contractFingerprint")), "missing");
        String receivedRouteManifestFingerprint = firstKnown(
            string(safeWire.get("routeManifestFingerprint")),
            "missing"
        );
        int receivedSchemaVersion = intValue(safeWire.get("schemaVersion"), DataAuthority.COMMAND_SCHEMA_VERSION);

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("expectedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        contract.put("receivedSchemaVersion", receivedSchemaVersion);
        contract.put("expectedFingerprint", DataAuthorityCommandContracts.fingerprint());
        contract.put("receivedFingerprint", receivedContractFingerprint);
        contract.put("expectedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        contract.put("receivedRouteManifestFingerprint", receivedRouteManifestFingerprint);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("guardEvidenceVersion", EVIDENCE_VERSION);
        evidence.put("phase", "PRE_SUBMIT_REFUSAL");
        evidence.put("commandId", result.commandId().toString());
        evidence.put("declarationId", firstKnown(string(safeWire.get("declarationId")), "unknown"));
        evidence.put("aggregateScope", firstKnown(string(safeWire.get("scope")), "unknown"));
        evidence.put("idempotencyKey", firstKnown(string(safeWire.get("idempotencyKey")), "unknown"));
        evidence.put("claimedActor", firstKnown(string(safeWire.get("actorId")), "unknown"));
        evidence.put("verifiedPrincipal", AuthorityPrincipals.nodePrincipal(firstKnown(originNode, "unknown")));
        evidence.put("originNode", firstKnown(originNode, "unknown"));
        evidence.put("targetNode", firstKnown(targetNode, "authority"));
        evidence.put("contract", contract);
        evidence.put("route", stringObjectMap(mapValue(safeWire.get("route"))));
        attachPreSubmitTopology(evidence, safeWire);
        evidence.put("deadline", Map.of(
            "deadlineEpochMillis", longValue(safeWire.get("deadlineEpochMillis"), 0L),
            "declared", longValue(safeWire.get("deadlineEpochMillis"), 0L) > 0L
        ));
        evidence.put("fencing", Map.of(
            "token", firstKnown(string(safeWire.get("fencingToken")), "none"),
            "declared", string(safeWire.get("fencingToken")) != null
        ));
        evidence.put("revision", Map.of(
            "expectedRevision", longValue(safeWire.get("expectedRevision"), DataAuthority.ANY_REVISION),
            "requiresCompare", longValue(safeWire.get("expectedRevision"), DataAuthority.ANY_REVISION)
                != DataAuthority.ANY_REVISION
        ));
        evidence.put("payloadHash", firstKnown(payloadHash, "missing"));
        evidence.put("result", resultPayload(result, "NOT_REPLAYABLE"));
        evidence.put("decisions", refusalGuardDecisions(
            result,
            receivedSchemaVersion,
            receivedContractFingerprint,
            receivedRouteManifestFingerprint
        ));
        return of(evidence);
    }

    private static Map<String, Object> base(
        String phase,
        DataAuthority.AuthorityCommand command,
        AuthorityCommandFrame frame,
        AuthorityCommandFingerprints.Fingerprint fingerprint
    ) {
        AuthorityCommandRoute route = frame.route();
        AuthorityCommandLane writerLane = AuthorityCommandLane.fromRoute(
            route,
            AuthorityCommandLane.DEFAULT_LANE_COUNT
        );
        DataAuthorityCommandContracts.CommandContract commandContract =
            DataAuthorityCommandContracts.contract(command.type());
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("expectedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        contract.put("receivedSchemaVersion", command.manifest().schemaVersion());
        contract.put("expectedFingerprint", DataAuthorityCommandContracts.fingerprint());
        contract.put("receivedFingerprint", DataAuthorityCommandContracts.fingerprint());
        contract.put("expectedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        contract.put("receivedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        contract.put("deliveryMode", commandContract.deliveryMode().name());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("guardEvidenceVersion", EVIDENCE_VERSION);
        evidence.put("phase", phase);
        evidence.put("commandId", command.commandId().toString());
        evidence.put("declarationId", commandContract.declarationId());
        evidence.put("aggregateScope", command.scope());
        evidence.put("idempotencyKey", command.idempotencyKey());
        evidence.put("claimedActor", command.actorId());
        evidence.put("verifiedPrincipal", command.provenance().verifiedPrincipal());
        evidence.put("originNode", command.provenance().originNode());
        evidence.put("authorityRoute", command.provenance().authorityRoute());
        evidence.put("providerKind", command.provenance().providerKind());
        evidence.put("contract", contract);
        evidence.put("route", route.payload());
        evidence.put("writerLane", writerLane.payload());
        AuthorityTopologyEvidence.attach(evidence, command, route);
        evidence.put("deadline", Map.of(
            "deadlineEpochMillis", command.deadlineEpochMillis(),
            "declared", command.deadlineEpochMillis() > 0L
        ));
        evidence.put("fencing", Map.of(
            "token", command.fencingToken().isBlank() ? "none" : command.fencingToken(),
            "declared", !command.fencingToken().isBlank()
        ));
        evidence.put("revision", Map.of(
            "expectedRevision", command.expectedRevision(),
            "requiresCompare", command.expectedRevision() != DataAuthority.ANY_REVISION
        ));
        evidence.put("payloadHash", fingerprint.payloadHash());
        evidence.put("commandFingerprint", fingerprint.commandFingerprint());
        return evidence;
    }

    private static List<Map<String, Object>> commandGuardDecisions(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        DataAuthority.RejectionReason reason = result == null
            ? DataAuthority.RejectionReason.NONE
            : result.rejectionReason();
        List<Map<String, Object>> decisions = new ArrayList<>();
        decisions.add(decision(
            "schemaContract",
            command.manifest().schemaVersion() == DataAuthority.COMMAND_SCHEMA_VERSION ? PASSED : FAILED,
            "command schema and contract fingerprint used by executable authority contract",
            Map.of(
                "expectedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION,
                "receivedSchemaVersion", command.manifest().schemaVersion(),
                "contractFingerprint", DataAuthorityCommandContracts.fingerprint()
            )
        ));
        decisions.add(decision(
            "routeManifest",
            PASSED,
            "command route policy manifest used by executable authority route contract",
            Map.of(
                "routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint(),
                "route", AuthorityCommandRoute.fromCommand(command).payload()
            )
        ));
        decisions.add(decision(
            "deadline",
            reason == DataAuthority.RejectionReason.EXPIRED_DEADLINE ? FAILED : OBSERVED,
            "deadline carried by command manifest",
            Map.of("deadlineEpochMillis", command.deadlineEpochMillis())
        ));
        decisions.add(decision(
            "principal",
            reason == DataAuthority.RejectionReason.INVALID_ACTOR ? FAILED : OBSERVED,
            "claimed actor compared with transport verified principal by authority principal guard",
            Map.of(
                "claimedActor", command.actorId(),
                "verifiedPrincipal", command.provenance().verifiedPrincipal()
            )
        ));
        decisions.add(decision(
            "routeAndScope",
            reason == DataAuthority.RejectionReason.INVALID_SCOPE ? FAILED : OBSERVED,
            "command route and aggregate scope bound to command contract",
            AuthorityCommandRoute.fromCommand(command).payload()
        ));
        decisions.add(decision(
            "writerLane",
            OBSERVED,
            "command partition key deterministically assigns one authority writer lane",
            AuthorityCommandLane.fromCommand(command, AuthorityCommandLane.DEFAULT_LANE_COUNT).payload()
        ));
        decisions.add(decision(
            "fencing",
            reason == DataAuthority.RejectionReason.STALE_FENCING_TOKEN ? FAILED : OBSERVED,
            "fencing token carried to authority writer",
            Map.of("fencingToken", command.fencingToken().isBlank() ? "none" : command.fencingToken())
        ));
        decisions.add(decision(
            "writerClaimReceipt",
            writerClaimVerdict(result),
            "database-minted writer claim receipt returned by authority settlement",
            result == null ? Map.of("claimBacked", false) : writerClaimPayload(result.settlement().fencingToken())
        ));
        decisions.add(decision(
            "revision",
            reason == DataAuthority.RejectionReason.STALE_REVISION ? FAILED : revisionVerdict(command),
            "expected revision carried to authority writer",
            Map.of("expectedRevision", command.expectedRevision())
        ));
        decisions.add(decision(
            "idempotency",
            reason == DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT ? FAILED : OBSERVED,
            "idempotency key carried to authority writer and command cache",
            Map.of("idempotencyKey", command.idempotencyKey())
        ));
        if (result != null) {
            decisions.add(decision(
                "terminalOutcome",
                result.accepted() ? PASSED : FAILED,
                result.rejectionReason().name(),
                Map.of(
                    "accepted", result.accepted(),
                    "resultRevision", result.revision(),
                    "settled", result.settlement().settled()
                )
            ));
        }
        return List.copyOf(decisions);
    }

    private static List<Map<String, Object>> refusalGuardDecisions(
        DataAuthority.CommandResult result,
        int receivedSchemaVersion,
        String receivedContractFingerprint,
        String receivedRouteManifestFingerprint
    ) {
        List<Map<String, Object>> decisions = new ArrayList<>();
        decisions.add(decision(
            "schemaContract",
            receivedSchemaVersion == DataAuthority.COMMAND_SCHEMA_VERSION
                && DataAuthorityCommandContracts.fingerprint().equals(receivedContractFingerprint)
                ? PASSED
                : FAILED,
            "pre-submit wire contract compared with executable authority contract",
            Map.of(
                "expectedSchemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION,
                "receivedSchemaVersion", receivedSchemaVersion,
                "expectedFingerprint", DataAuthorityCommandContracts.fingerprint(),
                "receivedFingerprint", receivedContractFingerprint
            )
        ));
        decisions.add(decision(
            "routeManifest",
            DataAuthorityCommandContracts.routeManifestFingerprint().equals(receivedRouteManifestFingerprint)
                ? PASSED
                : FAILED,
            "pre-submit route policy manifest compared with executable authority route contract",
            Map.of(
                "expectedRouteManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint(),
                "receivedRouteManifestFingerprint", receivedRouteManifestFingerprint
            )
        ));
        decisions.add(decision(
            "principal",
            result.rejectionReason() == DataAuthority.RejectionReason.INVALID_ACTOR ? FAILED : OBSERVED,
            "pre-submit authority provider principal decision",
            Map.of("rejectionReason", result.rejectionReason().name())
        ));
        decisions.add(decision(
            "routeAndScope",
            result.rejectionReason() == DataAuthority.RejectionReason.INVALID_SCOPE ? FAILED : OBSERVED,
            "pre-submit command route and scope decision",
            Map.of("rejectionReason", result.rejectionReason().name())
        ));
        decisions.add(decision(
            "terminalOutcome",
            FAILED,
            result.rejectionReason().name(),
            Map.of(
                "accepted", result.accepted(),
                "resultRevision", result.revision(),
                "replayEligibility", "NOT_REPLAYABLE"
            )
        ));
        return List.copyOf(decisions);
    }

    private static void attachPreSubmitTopology(Map<String, Object> evidence, Map<String, Object> wire) {
        try {
            DataAuthorityCommandContracts.CommandContract contract =
                DataAuthorityCommandContracts.contractByDeclarationId(
                    firstKnown(string(wire.get("declarationId")), "unknown")
                );
            AuthorityTopologyEvidence.attach(
                evidence,
                contract.type(),
                string(wire.get("scope")),
                mapValue(wire.get("route"))
            );
        } catch (RuntimeException ignored) {
            evidence.put("topology", Map.of(
                "topologyEvidenceVersion", 1,
                "status", "UNRESOLVED",
                "reason", "declaration id could not be resolved",
                "commandContractFingerprint", DataAuthorityCommandContracts.fingerprint(),
                "routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint(),
                "readContractFingerprint", DataAuthorityReadContracts.fingerprint(),
                "authorityDomainTopologyFingerprint", AuthorityDomainTopology.fingerprint(),
                "authorityStorePlacementFingerprint", AuthorityStorePlacements.fingerprint(),
                "authorityLogTopologyFingerprint", AuthorityTopologyEvidence.logTopologyFingerprint()
            ));
        }
    }

    private static String revisionVerdict(DataAuthority.AuthorityCommand command) {
        return command.expectedRevision() == DataAuthority.ANY_REVISION ? SKIPPED : OBSERVED;
    }

    private static String writerClaimVerdict(DataAuthority.CommandResult result) {
        if (result == null || !result.accepted()) {
            return SKIPPED;
        }
        return writerClaimToken(result.settlement().fencingToken()) == null ? FAILED : PASSED;
    }

    private static Map<String, Object> resultPayload(
        DataAuthority.CommandResult result,
        String replayEligibility
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("accepted", result.accepted());
        values.put("revision", result.revision());
        values.put("rejectionReason", result.rejectionReason().name());
        values.put("message", result.message());
        values.put("replayEligibility", replayEligibility == null ? "UNKNOWN" : replayEligibility);
        values.put("settlement", result.settlement().payload());
        return values;
    }

    private static Map<String, Object> decision(
        String guard,
        String verdict,
        String reason,
        Map<String, Object> values
    ) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("guard", guard);
        decision.put("verdict", verdict);
        decision.put("reason", reason);
        decision.put("values", values == null ? Map.of() : values);
        return decision;
    }

    private static GuardEvidence of(Map<String, Object> payload) {
        Map<String, Object> immutablePayload = Map.copyOf(payload);
        return new GuardEvidence(
            immutablePayload,
            AuthorityCommandFingerprints.hash(AuthorityCommandFingerprints.canonicalJson(immutablePayload))
        );
    }

    private static Map<String, Object> writerClaimPayload(String fencingToken) {
        AuthorityWriterClaimToken claim = writerClaimToken(fencingToken);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("token", fencingToken == null || fencingToken.isBlank() ? "none" : fencingToken);
        values.put("declared", fencingToken != null && !fencingToken.isBlank());
        values.put("claimBacked", claim != null);
        if (claim != null) {
            values.put("epoch", claim.epoch());
            values.put("claimId", claim.claimId().toString());
            values.put("claimFingerprint", claim.claimFingerprint());
        }
        return Map.copyOf(values);
    }

    private static AuthorityWriterClaimToken writerClaimToken(String fencingToken) {
        try {
            return AuthorityWriterClaimToken.parse(fencingToken);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
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

    record GuardEvidence(Map<String, Object> payload, String fingerprint) {
        GuardEvidence {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("guard evidence fingerprint must be a SHA-256 hash");
            }
        }
    }
}
