package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.presence.ClaimPresence;
import sh.harold.fulcrum.data.presence.HeartbeatPresence;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.presence.PresenceLifecycleStatus;
import sh.harold.fulcrum.data.presence.PresenceOwnerToken;
import sh.harold.fulcrum.data.presence.PresenceReceipt;
import sh.harold.fulcrum.data.presence.PresenceReceiptStatus;
import sh.harold.fulcrum.data.presence.PresenceReleaseReason;
import sh.harold.fulcrum.data.presence.PresenceSnapshot;
import sh.harold.fulcrum.data.presence.PresenceState;
import sh.harold.fulcrum.data.presence.ReleasePresence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PresenceAuthorityWireCodec {
    static final String CONTRACT = "presence";
    static final String CLAIM_COMMAND = "claim-presence";
    static final String HEARTBEAT_COMMAND = "heartbeat-presence";
    static final String RELEASE_COMMAND = "release-presence";

    private PresenceAuthorityWireCodec() {
    }

    static AuthorityCommand<PresenceCommand> decodeCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        PresenceCommand payload = decodePayload(fields);
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        new PrincipalId(firstRequired(fields, "declaredPrincipalId", "principalId")),
                        new AggregateId(optional(fields, "aggregateId").orElse(record.key())),
                        new ContractName(optional(fields, "contractName").orElse(CONTRACT)),
                        new CommandName(required(fields, "commandName")),
                        decodeTrace(fields),
                        optionalInstant(fields, "deadlineAt"),
                        payload),
                new PrincipalId(firstRequired(fields, "authenticatedPrincipalId", "authenticatedPrincipal")),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeCommand(AuthorityCommand<PresenceCommand> command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        CommandEnvelope<PresenceCommand> envelope = command.envelope();
        fields.put("commandId", envelope.commandId().value());
        fields.put("idempotencyKey", envelope.idempotencyKey().value());
        fields.put("principalId", envelope.principalId().value());
        fields.put("declaredPrincipalId", envelope.principalId().value());
        fields.put("aggregateId", envelope.aggregateId().value());
        fields.put("contractName", envelope.contractName().value());
        fields.put("commandName", envelope.commandName().value());
        encodeTrace(fields, envelope.traceEnvelope());
        fields.put("deadlineAt", envelope.deadlineAt().map(Instant::toString).orElse(""));
        fields.put("authenticatedPrincipal", command.authenticatedPrincipal().value());
        fields.put("authenticatedPrincipalId", command.authenticatedPrincipal().value());
        fields.put("fencingEpoch", Long.toString(command.fencingEpoch()));
        fields.put("expectedRevision", command.expectedRevision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", command.payloadFingerprint());
        fields.put("receivedAt", command.receivedAt().toString());
        encodePayload(fields, envelope.payload());
        return lines(fields);
    }

    static String encodeState(PresenceState state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("current", Boolean.toString(state.current().isPresent()));
        state.current().ifPresent(snapshot -> encodeSnapshot(fields, "", snapshot));
        return lines(fields);
    }

    static PresenceState decodeState(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "current"))) {
            return new PresenceState(Optional.empty());
        }
        return new PresenceState(decodeSnapshot(fields, ""));
    }

    static String encodeReceipt(PresenceReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", receipt.status().name());
        fields.put("reason", receipt.rejectionReason().orElse(""));
        fields.put("presenceId", receipt.presenceId().map(PresenceId::value).orElse(""));
        fields.put("subjectId", receipt.subjectId().map(value -> value.value().toString()).orElse(""));
        fields.put("revision", receipt.revision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("fencingEpoch", receipt.fencingEpoch().map(Object::toString).orElse(""));
        fields.put("ownerEpoch", receipt.ownerEpoch().map(Object::toString).orElse(""));
        fields.put("lifecycleStatus", receipt.lifecycleStatus().map(PresenceLifecycleStatus::name).orElse(""));
        fields.put("idempotencyKey", receipt.idempotencyKey().orElse(""));
        fields.put("commandId", receipt.commandId().orElse(""));
        return lines(fields);
    }

    static PresenceReceipt decodeReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        return new PresenceReceipt(
                PresenceReceiptStatus.valueOf(required(fields, "status")),
                optional(fields, "reason"),
                optional(fields, "presenceId").map(PresenceId::new),
                optionalSubjectId(fields, "subjectId"),
                optionalRevision(fields, "revision"),
                optionalLong(fields, "fencingEpoch"),
                optionalLong(fields, "ownerEpoch"),
                optional(fields, "lifecycleStatus").map(PresenceLifecycleStatus::valueOf),
                optional(fields, "idempotencyKey"),
                optional(fields, "commandId"));
    }

    static String encodeStoredDecision(StoredAuthorityDecision<PresenceState, PresenceReceipt> stored) {
        Objects.requireNonNull(stored, "stored");
        AuthorityDecision<PresenceState, PresenceReceipt> decision = stored.decision();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payloadFingerprint", stored.payloadFingerprint());
        fields.put("decisionStatus", decision.status().name());
        fields.put("rejectionReason", decision.rejectionReason().map(Enum::name).orElse(""));
        fields.put("revision", Long.toString(decision.revision().value()));
        fields.put("replayed", Boolean.toString(decision.replayed()));
        encodeTrace(fields, decision.traceEnvelope());
        prefixed(fields, "state.", encodeState(decision.state()));
        prefixed(fields, "response.", encodeReceipt(decision.response()));
        return lines(fields);
    }

    static StoredAuthorityDecision<PresenceState, PresenceReceipt> decodeStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecision<PresenceState, PresenceReceipt> decision = new AuthorityDecision<>(
                AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus")),
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf),
                new Revision(longValue(fields, "revision")),
                decodeState(unprefixed(fields, "state.")),
                decodeReceipt(unprefixed(fields, "response.")),
                List.of(),
                decodeTrace(fields),
                Boolean.parseBoolean(required(fields, "replayed")));
        return new StoredAuthorityDecision<>(required(fields, "payloadFingerprint"), decision);
    }

    static String encodeDecisionPayload(AuthorityDecision<PresenceState, PresenceReceipt> decision) {
        return encodeStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    private static PresenceCommand decodePayload(Map<String, String> fields) {
        SubjectId subjectId = subjectId(required(fields, "subjectId"));
        return switch (required(fields, "commandName")) {
            case CLAIM_COMMAND -> new ClaimPresence(
                    new PresenceId(required(fields, "presenceId")),
                    subjectId,
                    new InstanceId(required(fields, "ownerInstanceId")),
                    new PresenceOwnerToken(required(fields, "ownerToken")),
                    optional(fields, "sessionId").map(SessionId::new),
                    optional(fields, "routeId").map(RouteId::new),
                    instant(fields, "observedAt"),
                    instant(fields, "expiresAt"));
            case HEARTBEAT_COMMAND -> new HeartbeatPresence(
                    subjectId,
                    new PresenceOwnerToken(required(fields, "ownerToken")),
                    longValue(fields, "ownerEpoch"),
                    instant(fields, "observedAt"),
                    instant(fields, "expiresAt"));
            case RELEASE_COMMAND -> new ReleasePresence(
                    subjectId,
                    new PresenceOwnerToken(required(fields, "ownerToken")),
                    longValue(fields, "ownerEpoch"),
                    instant(fields, "releasedAt"),
                    PresenceReleaseReason.valueOf(required(fields, "releaseReason")));
            default -> throw new IllegalArgumentException("Unsupported Presence command " + required(fields, "commandName"));
        };
    }

    private static void encodePayload(Map<String, String> fields, PresenceCommand payload) {
        if (payload instanceof ClaimPresence claim) {
            fields.put("subjectId", claim.subjectId().value().toString());
            fields.put("presenceId", claim.presenceId().value());
            fields.put("ownerInstanceId", claim.ownerInstanceId().value());
            fields.put("ownerToken", claim.ownerToken().value());
            fields.put("sessionId", claim.sessionId().map(SessionId::value).orElse(""));
            fields.put("routeId", claim.routeId().map(RouteId::value).orElse(""));
            fields.put("observedAt", claim.observedAt().toString());
            fields.put("expiresAt", claim.expiresAt().toString());
            return;
        }
        if (payload instanceof HeartbeatPresence heartbeat) {
            fields.put("subjectId", heartbeat.subjectId().value().toString());
            fields.put("ownerToken", heartbeat.ownerToken().value());
            fields.put("ownerEpoch", Long.toString(heartbeat.ownerEpoch()));
            fields.put("observedAt", heartbeat.observedAt().toString());
            fields.put("expiresAt", heartbeat.expiresAt().toString());
            return;
        }
        if (payload instanceof ReleasePresence release) {
            fields.put("subjectId", release.subjectId().value().toString());
            fields.put("ownerToken", release.ownerToken().value());
            fields.put("ownerEpoch", Long.toString(release.ownerEpoch()));
            fields.put("releasedAt", release.releasedAt().toString());
            fields.put("releaseReason", release.reason().name());
            return;
        }
        throw new IllegalArgumentException("Unsupported Presence command " + payload.getClass().getSimpleName());
    }

    private static void encodeSnapshot(Map<String, String> fields, String prefix, PresenceSnapshot snapshot) {
        fields.put(prefix + "presenceId", snapshot.presenceId().value());
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "ownerInstanceId", snapshot.ownerInstanceId().value());
        fields.put(prefix + "ownerToken", snapshot.ownerToken().value());
        fields.put(prefix + "ownerEpoch", Long.toString(snapshot.ownerEpoch()));
        fields.put(prefix + "status", snapshot.status().name());
        fields.put(prefix + "sessionId", snapshot.sessionId().map(SessionId::value).orElse(""));
        fields.put(prefix + "routeId", snapshot.routeId().map(RouteId::value).orElse(""));
        fields.put(prefix + "observedAt", snapshot.observedAt().toString());
        fields.put(prefix + "expiresAt", snapshot.expiresAt().toString());
        fields.put(prefix + "releasedAt", snapshot.releasedAt().map(Instant::toString).orElse(""));
        fields.put(prefix + "releaseReason", snapshot.releaseReason().map(PresenceReleaseReason::name).orElse(""));
    }

    private static PresenceSnapshot decodeSnapshot(Map<String, String> fields, String prefix) {
        return new PresenceSnapshot(
                new PresenceId(required(fields, prefix + "presenceId")),
                subjectId(required(fields, prefix + "subjectId")),
                new InstanceId(required(fields, prefix + "ownerInstanceId")),
                new PresenceOwnerToken(required(fields, prefix + "ownerToken")),
                longValue(fields, prefix + "ownerEpoch"),
                PresenceLifecycleStatus.valueOf(required(fields, prefix + "status")),
                optional(fields, prefix + "sessionId").map(SessionId::new),
                optional(fields, prefix + "routeId").map(RouteId::new),
                instant(fields, prefix + "observedAt"),
                instant(fields, prefix + "expiresAt"),
                optionalInstant(fields, prefix + "releasedAt"),
                optional(fields, prefix + "releaseReason").map(PresenceReleaseReason::valueOf));
    }

    private static void encodeTrace(Map<String, String> fields, TraceEnvelope trace) {
        fields.put("traceId", trace.traceId());
        fields.put("spanId", trace.spanId());
        fields.put("parentSpanId", trace.parentSpanId().orElse(""));
        fields.put("traceCreatedAt", trace.createdAt().toString());
        fields.put("originService", trace.originService());
        fields.put("originInstanceId", trace.originInstanceId().value());
    }

    private static TraceEnvelope decodeTrace(Map<String, String> fields) {
        return new TraceEnvelope(
                required(fields, "traceId"),
                required(fields, "spanId"),
                optional(fields, "parentSpanId"),
                optionalInstant(fields, "traceCreatedAt")
                        .orElseGet(() -> instant(fields, "requestedAt")),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static void prefixed(Map<String, String> target, String prefix, String payload) {
        fields(payload).forEach((key, value) -> target.put(prefix + key, value));
    }

    private static String unprefixed(Map<String, String> source, String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                values.put(key.substring(prefix.length()), value);
            }
        });
        return lines(values);
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed Presence authority wire line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static String lines(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=').append(value == null ? "" : value).append('\n'));
        return builder.toString();
    }

    private static String firstRequired(Map<String, String> fields, String first, String second) {
        return optional(fields, first).or(() -> optional(fields, second))
                .orElseThrow(() -> new IllegalArgumentException("Missing Presence authority wire field " + first));
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Presence authority wire field " + key);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static long longValue(Map<String, String> fields, String key) {
        return Long.parseLong(required(fields, key));
    }

    private static Optional<Long> optionalLong(Map<String, String> fields, String key) {
        return optional(fields, key).map(Long::parseLong);
    }

    private static Optional<Revision> optionalRevision(Map<String, String> fields, String key) {
        return optionalLong(fields, key).map(Revision::new);
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }

    private static Optional<Instant> optionalInstant(Map<String, String> fields, String key) {
        return optional(fields, key).map(Instant::parse);
    }

    private static Optional<SubjectId> optionalSubjectId(Map<String, String> fields, String key) {
        return optional(fields, key).map(PresenceAuthorityWireCodec::subjectId);
    }

    private static SubjectId subjectId(String value) {
        return new SubjectId(UUID.fromString(value));
    }
}
