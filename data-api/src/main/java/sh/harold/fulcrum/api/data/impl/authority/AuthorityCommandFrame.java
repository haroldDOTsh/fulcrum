package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record AuthorityCommandFrame(
    UUID commandId,
    DataAuthority.CommandType type,
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
        if (type == null) {
            type = contract.type();
        }
        if (contract.type() != type) {
            throw new IllegalArgumentException(
                "Command declaration " + declarationId + " does not match command type " + type
            );
        }
        route = route == null ? AuthorityCommandRoute.from(type, scope) : route;
        provenance = provenance == null ? DataAuthority.CommandProvenance.unknown() : provenance;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        DataAuthorityCommandContracts.validate(
            type,
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
        DataAuthorityCommandContracts.CommandContract contract = DataAuthorityCommandContracts.contract(manifest.type());
        return new AuthorityCommandFrame(
            manifest.commandId(),
            manifest.type(),
            contract.declarationId(),
            manifest.actorId(),
            manifest.scope(),
            manifest.idempotencyKey(),
            manifest.deadlineEpochMillis(),
            manifest.fencingToken(),
            manifest.expectedRevision(),
            manifest.schemaVersion(),
            AuthorityCommandRoute.fromCommand(command),
            manifest.provenance(),
            AuthorityCommandPayloads.payload(command)
        );
    }

    static AuthorityCommandFrame fromPayloads(Map<String, Object> manifestPayload, Map<String, Object> commandPayload) {
        String declarationId = string(manifestPayload.get("declarationId"));
        DataAuthorityCommandContracts.CommandContract contract =
            DataAuthorityCommandContracts.contractByDeclarationId(declarationId);
        DataAuthority.CommandType type = contract.type();
        String scope = string(manifestPayload.get("scope"));
        DataAuthority.CommandProvenance provenance = provenance(mapValue(manifestPayload.get("provenance")));
        return new AuthorityCommandFrame(
            uuid(manifestPayload.get("commandId")),
            type,
            declarationId,
            string(manifestPayload.get("actorId")),
            scope,
            string(manifestPayload.get("idempotencyKey")),
            longValue(manifestPayload.get("deadlineEpochMillis"), 0L),
            string(manifestPayload.get("fencingToken")),
            longValue(manifestPayload.get("expectedRevision"), DataAuthority.ANY_REVISION),
            intValue(manifestPayload.get("schemaVersion"), DataAuthority.COMMAND_SCHEMA_VERSION),
            AuthorityCommandRoute.fromPayload(mapValue(manifestPayload.get("route")), type, scope),
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
        DataAuthority.CommandManifest manifest = new DataAuthority.CommandManifest(
            commandId,
            type,
            actorId,
            scope,
            idempotencyKey,
            deadlineEpochMillis,
            fencingToken,
            expectedRevision,
            schemaVersion,
            provenance
        );
        DataAuthority.AuthorityCommand command = switch (type) {
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT -> new DataAuthority.PlayerProfileCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("username")),
                longValue(payload.get("timestamp"), System.currentTimeMillis()),
                string(payload.get("currentServer")),
                string(payload.get("currentProxy")),
                string(payload.get("lastIp")),
                string(payload.get("lastWorld")),
                string(payload.get("lastLocation")),
                string(payload.get("gamemode")),
                nullableInt(payload.get("level")),
                nullableFloat(payload.get("exp")),
                nullableDouble(payload.get("health")),
                nullableInt(payload.get("foodLevel")),
                string(payload.get("playtimeStartField"))
            );
            case START_SESSION, RENEW_SESSION, END_SESSION -> new DataAuthority.PlayerSessionCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("username")),
                nullableUuid(payload.get("sessionId")),
                longValue(payload.get("timestamp"), System.currentTimeMillis()),
                string(payload.get("currentServer")),
                string(payload.get("currentProxy")),
                string(payload.get("lastIp")),
                nullableInt(payload.get("protocolVersion")),
                string(payload.get("disconnectReason"))
            );
            case GRANT_RANK, REVOKE_RANK -> new DataAuthority.PlayerRankCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("primaryRank")),
                stringList(payload.get("ranks"))
            );
            case RECORD_MATCH_START, RECORD_MATCH_END -> new DataAuthority.MatchCommand(
                manifest,
                uuid(payload.get("matchId")),
                string(payload.get("familyId")),
                string(payload.get("mapId")),
                string(payload.get("serverId")),
                string(payload.get("slotId")),
                string(payload.get("state")),
                nullableLong(payload.get("startedAt")),
                nullableLong(payload.get("endedAt")),
                stringObjectMap(mapValue(payload.get("slotMetadata"))),
                participants(payload.get("participants"))
            );
        };
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

    private static List<DataAuthority.MatchParticipant> participants(Object rawParticipants) {
        if (!(rawParticipants instanceof Iterable<?> rawValues)) {
            return List.of();
        }
        List<DataAuthority.MatchParticipant> participants = new ArrayList<>();
        for (Object raw : rawValues) {
            Map<String, Object> values = stringObjectMap(mapValue(raw));
            Object rawPlayerId = values.get("playerId");
            if (rawPlayerId == null) {
                continue;
            }
            participants.add(new DataAuthority.MatchParticipant(
                uuid(rawPlayerId),
                string(values.get("teamId")),
                nullableInt(values.get("placement")),
                string(values.get("state")),
                stringObjectMap(mapValue(values.get("stats")))
            ));
        }
        return participants;
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
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

    private static UUID nullableUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : uuid(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : longValue(value, 0L);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : intValue(value, 0);
    }

    private static Float nullableFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return value == null ? null : Float.parseFloat(value.toString());
    }

    private static Double nullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? null : Double.parseDouble(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
