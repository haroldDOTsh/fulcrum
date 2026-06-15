package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AuthorityLogFrames {
    private AuthorityLogFrames() {
    }

    static AuthorityLogRecord appendCommand(AuthorityLog log, DataAuthority.AuthorityCommand command) {
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(command);
        AuthorityCommandFingerprints.Fingerprint fingerprint = AuthorityCommandFingerprints.fingerprint(command);
        return log.append(frame.route(), AuthorityLogTopicKind.COMMAND, commandPayload(command, frame, fingerprint));
    }

    static DataAuthority.CommandResult appendTerminal(
        AuthorityLog log,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        return appendTerminal(log, command, result, null);
    }

    static DataAuthority.CommandResult appendTerminal(
        AuthorityLog log,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        AuthorityLogWorkerToken workerToken
    ) {
        DataAuthority.CommandResult normalized = normalize(command, result);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        DataAuthority.CommandResult logPositioned = normalized;
        if (normalized.accepted() && normalized.settlement().settled()) {
            appendEvent(log, command, normalized, route, workerToken);
            AuthorityLogRecord stateRecord = appendState(log, command, normalized, route, workerToken);
            logPositioned = normalized.withSettlement(positionedSettlement(normalized.settlement(), stateRecord));
        }
        log.append(route, AuthorityLogTopicKind.RESPONSE, responsePayload(command, logPositioned, workerToken));
        return logPositioned;
    }

    static DataAuthority.CommandResult storeUnavailable(
        DataAuthority.AuthorityCommand command,
        Throwable failure
    ) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            false,
            command.expectedRevision(),
            DataAuthority.RejectionReason.STORE_UNAVAILABLE,
            "Authority log command port failed: " + message(failure)
        );
    }

    private static AuthorityLogRecord appendEvent(
        AuthorityLog log,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        AuthorityCommandRoute route,
        AuthorityLogWorkerToken workerToken
    ) {
        DataAuthority.CommandSettlement settlement = result.settlement();
        DataAuthority.SnapshotWatermark watermark = settlement.watermark();
        MapBuilder payload = new MapBuilder()
            .put("frameType", "EVENT")
            .put("commandId", command.commandId().toString())
            .put("commandType", command.type().name())
            .put("aggregateScope", command.scope())
            .put("aggregateType", watermark.aggregateType())
            .put("aggregateId", watermark.aggregateId())
            .put("revision", result.revision())
            .put("eventId", string(watermark.sourceEventId()))
            .put("eventChainHash", watermark.eventChainHash())
            .put("eventCreatedEpochMillis", watermark.eventCreatedEpochMillis())
            .put("settlement", settlement.payload());
        putWorkerToken(payload, workerToken);
        return log.append(route, AuthorityLogTopicKind.EVENT, payload.build());
    }

    private static AuthorityLogRecord appendState(
        AuthorityLog log,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        AuthorityCommandRoute route,
        AuthorityLogWorkerToken workerToken
    ) {
        DataAuthority.CommandSettlement settlement = result.settlement();
        DataAuthority.SnapshotWatermark watermark = settlement.watermark();
        MapBuilder payload = new MapBuilder()
            .put("frameType", "STATE")
            .put("commandId", command.commandId().toString())
            .put("aggregateScope", command.scope())
            .put("aggregateType", watermark.aggregateType())
            .put("aggregateId", watermark.aggregateId())
            .put("revision", result.revision())
            .put("commandDomain", settlement.commandDomain())
            .put("stateTopic", settlement.stateTopic())
            .put("partitionKey", settlement.partitionKey())
            .put("eventId", string(watermark.sourceEventId()))
            .put("eventCreatedEpochMillis", watermark.eventCreatedEpochMillis())
            .put("stateFingerprint", watermark.stateFingerprint())
            .put("eventChainHash", watermark.eventChainHash())
            .put("statePayload", settlement.statePayload())
            .put("watermark", watermark.payload())
            .put("settlement", settlement.payload());
        putWorkerToken(payload, workerToken);
        return log.append(route, AuthorityLogTopicKind.STATE, payload.build());
    }

    private static Map<String, Object> commandPayload(
        DataAuthority.AuthorityCommand command,
        AuthorityCommandFrame frame,
        AuthorityCommandFingerprints.Fingerprint fingerprint
    ) {
        return new MapBuilder()
            .put("frameType", "COMMAND")
            .put("commandId", command.commandId().toString())
            .put("commandType", command.type().name())
            .put("aggregateScope", command.scope())
            .put("idempotencyKey", command.idempotencyKey())
            .put("actorId", command.actorId())
            .put("verifiedPrincipal", command.provenance().verifiedPrincipal())
            .put("contractFingerprint", DataAuthorityCommandContracts.fingerprint())
            .put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint())
            .put("payloadHash", fingerprint.payloadHash())
            .put("commandFingerprint", fingerprint.commandFingerprint())
            .put("manifest", frame.manifestPayload())
            .put("payload", frame.payload())
            .build();
    }

    private static Map<String, Object> responsePayload(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        AuthorityLogWorkerToken workerToken
    ) {
        MapBuilder payload = new MapBuilder()
            .put("frameType", "RESPONSE")
            .put("commandId", result.commandId().toString())
            .put("commandType", command.type().name())
            .put("aggregateScope", command.scope())
            .put("accepted", result.accepted())
            .put("revision", result.revision())
            .put("rejectionReason", result.rejectionReason().name())
            .put("message", result.message())
            .put("settlement", result.settlement().payload());
        if (result.refusalReceipt() != null) {
            payload.put("refusalReceipt", result.refusalReceipt().payload());
        }
        putWorkerToken(payload, workerToken);
        return payload.build();
    }

    private static void putWorkerToken(MapBuilder payload, AuthorityLogWorkerToken workerToken) {
        if (workerToken != null) {
            workerToken.payload().forEach(payload::put);
        }
    }

    private static DataAuthority.CommandSettlement positionedSettlement(
        DataAuthority.CommandSettlement settlement,
        AuthorityLogRecord stateRecord
    ) {
        DataAuthority.SnapshotWatermark watermark = settlement.watermark();
        DataAuthority.SnapshotWatermark positionedWatermark = new DataAuthority.SnapshotWatermark(
            watermark.sourceProvider(),
            watermark.aggregateScope(),
            watermark.aggregateType(),
            watermark.aggregateId(),
            watermark.commandDomain(),
            watermark.stateTopic(),
            watermark.partitionKey(),
            watermark.sourceCommandId(),
            watermark.sourceEventId(),
            watermark.sourceRevision(),
            watermark.eventCreatedEpochMillis(),
            stateRecord.partition(),
            stateRecord.offset(),
            watermark.stateFingerprint(),
            watermark.eventChainHash()
        );
        return new DataAuthority.CommandSettlement(
            settlement.sourceProvider(),
            settlement.commandDomain(),
            settlement.commandTopic(),
            settlement.responseTopic(),
            settlement.eventTopic(),
            settlement.stateTopic(),
            settlement.partitionKey(),
            settlement.fencingToken(),
            settlement.idempotencyKey(),
            settlement.expectedRevision(),
            positionedWatermark,
            settlement.statePayload()
        );
    }

    private static DataAuthority.CommandResult normalize(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        if (result == null) {
            return storeUnavailable(command, new IllegalStateException("Authority returned no result"));
        }
        if (!command.commandId().equals(result.commandId())) {
            return storeUnavailable(command, new IllegalStateException(
                "Authority returned commandId " + result.commandId()
                    + " for command " + command.commandId()
            ));
        }
        return result;
    }

    private static String message(Throwable failure) {
        Throwable effective = failure == null ? null : failure.getCause() == null ? failure : failure.getCause();
        if (effective == null || effective.getMessage() == null || effective.getMessage().isBlank()) {
            return "unknown failure";
        }
        return effective.getMessage();
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static final class MapBuilder {
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        private MapBuilder put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }
}
