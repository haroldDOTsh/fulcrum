package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

record AuthorityCommandFrame(
    UUID commandId,
    String declarationId,
    String actorId,
    String scope,
    String idempotencyKey,
    long deadlineEpochMillis,
    String fencingToken,
    long expectedRevision,
    int schemaVersion,
    AuthorityCommandRoute route,
    DataAuthority.CommandProvenance provenance,
    Map<String, Object> payload
) {
    AuthorityCommandFrame {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        DataAuthorityCommandContracts.CommandContract contract =
            DataAuthorityCommandContracts.contractByDeclarationId(declarationId);
        route = route == null ? AuthorityCommandRoute.fromDeclarationId(declarationId, scope) : route;
        provenance = provenance == null ? DataAuthority.CommandProvenance.unknown() : provenance;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        DataAuthorityCommandContracts.validate(
            contract,
            schemaVersion,
            provenance.contractVersion(),
            scope,
            route,
            expectedRevision,
            payload
        );
    }

    static AuthorityCommandFrame fromCommand(DataAuthority.AuthorityCommand command) {
        DataAuthorityCommandContracts.validate(command);
        DataAuthority.CommandManifest manifest = command.manifest();
        DataAuthorityCommandContracts.CommandContract contract =
            DataAuthorityCommandContracts.contractByDeclarationId(manifest.declarationId());
        return new AuthorityCommandFrame(
            manifest.commandId(),
            contract.declarationId(),
            manifest.actorId(),
            manifest.scope(),
            manifest.idempotencyKey(),
            manifest.deadlineEpochMillis(),
            manifest.fencingToken(),
            manifest.expectedRevision(),
            manifest.schemaVersion(),
            AuthorityCommandRoute.fromDeclarationId(contract.declarationId(), manifest.scope()),
            manifest.provenance(),
            AuthorityCommandPayloads.payload(command)
        );
    }

    static AuthorityCommandFrame fromPayloads(Map<String, Object> manifestPayload, Map<String, Object> commandPayload) {
        String declarationId = string(manifestPayload.get("declarationId"));
        String scope = string(manifestPayload.get("scope"));
        DataAuthority.CommandProvenance provenance = provenance(mapValue(manifestPayload.get("provenance")));
        return new AuthorityCommandFrame(
            uuid(manifestPayload.get("commandId")),
            declarationId,
            string(manifestPayload.get("actorId")),
            scope,
            string(manifestPayload.get("idempotencyKey")),
            longValue(manifestPayload.get("deadlineEpochMillis"), 0L),
            string(manifestPayload.get("fencingToken")),
            longValue(manifestPayload.get("expectedRevision"), DataAuthority.ANY_REVISION),
            intValue(manifestPayload.get("schemaVersion"), DataAuthority.COMMAND_SCHEMA_VERSION),
            AuthorityCommandRoute.fromPayload(mapValue(manifestPayload.get("route")), declarationId, scope),
            provenance,
            commandPayload
        );
    }

    Map<String, Object> manifestPayload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("commandId", commandId.toString());
        values.put("declarationId", declarationId);
        values.put("actorId", actorId);
        values.put("scope", scope);
        values.put("idempotencyKey", idempotencyKey);
        values.put("deadlineEpochMillis", deadlineEpochMillis);
        values.put("fencingToken", fencingToken);
        values.put("expectedRevision", expectedRevision);
        values.put("schemaVersion", schemaVersion);
        values.put("contractFingerprint", DataAuthorityCommandContracts.fingerprint());
        values.put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        values.put("readContractFingerprint", DataAuthorityReadContracts.fingerprint());
        values.put("authorityDomainTopologyFingerprint", AuthorityDomainTopology.fingerprint());
        values.put("authorityStorePlacementFingerprint", AuthorityStorePlacements.fingerprint());
        values.put("authorityLogTopologyFingerprint", AuthorityTopologyEvidence.logTopologyFingerprint());
        values.put("route", route.payload());
        values.put("provenance", provenance.payload());
        return Map.copyOf(values);
    }

    DataAuthority.AuthorityCommand toCommand() {
        String commandDeclarationId = declarationId;
        DataAuthority.CommandManifest manifest = new DataAuthority.CommandManifest(
            commandId,
            commandDeclarationId,
            actorId,
            scope,
            idempotencyKey,
            deadlineEpochMillis,
            fencingToken,
            expectedRevision,
            schemaVersion,
            provenance
        );
        DataAuthority.AuthorityCommand command =
            AuthorityDomainDeclarations.command(commandDeclarationId).toCommand(manifest, payload);
        DataAuthorityCommandContracts.validate(command);
        return command;
    }

    private static DataAuthority.CommandProvenance provenance(Map<?, ?> raw) {
        return new DataAuthority.CommandProvenance(
            string(raw.get("originNode")),
            string(raw.get("authorityRoute")),
            string(raw.get("providerKind")),
            intValue(raw.get("contractVersion"), DataAuthority.COMMAND_SCHEMA_VERSION),
            string(raw.get("verifiedPrincipal"))
        );
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("UUID value is required");
        }
        return UUID.fromString(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
