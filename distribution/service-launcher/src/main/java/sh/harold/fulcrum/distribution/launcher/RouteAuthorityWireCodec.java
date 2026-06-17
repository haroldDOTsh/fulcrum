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
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.route.RouteLifecycleStatus;
import sh.harold.fulcrum.data.route.RouteReceipt;
import sh.harold.fulcrum.data.route.RouteReceiptStatus;
import sh.harold.fulcrum.data.route.RouteSnapshot;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.TimeoutRoute;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class RouteAuthorityWireCodec {
    static final String CONTRACT = "route";
    static final String OPEN_COMMAND = "open-route";
    static final String ACKNOWLEDGE_COMMAND = "acknowledge-route";
    static final String TIMEOUT_COMMAND = "timeout-route";

    private RouteAuthorityWireCodec() {
    }

    static AuthorityCommand<RouteCommand> decodeCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        RouteCommand payload = decodePayload(fields);
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        new PrincipalId(firstRequired(fields, "principalId", "declaredPrincipalId")),
                        new AggregateId(optional(fields, "aggregateId").orElse(record.key())),
                        new ContractName(optional(fields, "contractName").orElse(CONTRACT)),
                        new CommandName(required(fields, "commandName")),
                        decodeTrace(fields),
                        optionalInstant(fields, "deadlineAt"),
                        payload),
                new PrincipalId(firstRequired(fields, "authenticatedPrincipal", "authenticatedPrincipalId")),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeCommand(AuthorityCommand<RouteCommand> command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        CommandEnvelope<RouteCommand> envelope = command.envelope();
        fields.put("commandId", envelope.commandId().value());
        fields.put("idempotencyKey", envelope.idempotencyKey().value());
        fields.put("principalId", envelope.principalId().value());
        fields.put("aggregateId", envelope.aggregateId().value());
        fields.put("contractName", envelope.contractName().value());
        fields.put("commandName", envelope.commandName().value());
        encodeTrace(fields, envelope.traceEnvelope());
        fields.put("deadlineAt", envelope.deadlineAt().map(Instant::toString).orElse(""));
        fields.put("authenticatedPrincipal", command.authenticatedPrincipal().value());
        fields.put("fencingEpoch", Long.toString(command.fencingEpoch()));
        fields.put("expectedRevision", command.expectedRevision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", command.payloadFingerprint());
        fields.put("receivedAt", command.receivedAt().toString());
        encodePayload(fields, envelope.payload());
        return lines(fields);
    }

    static String encodeState(RouteState state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("current", Boolean.toString(state.current().isPresent()));
        state.current().ifPresent(snapshot -> encodeSnapshot(fields, "", snapshot));
        return lines(fields);
    }

    static RouteState decodeState(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "current"))) {
            return RouteState.empty();
        }
        return new RouteState(decodeSnapshot(fields, ""));
    }

    static String encodeReceipt(RouteReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", receipt.status().name());
        fields.put("reason", receipt.rejectionReason().orElse(""));
        fields.put("routeId", receipt.routeId().map(RouteId::value).orElse(""));
        fields.put("subjectId", receipt.subjectId().map(value -> value.value().toString()).orElse(""));
        fields.put("revision", receipt.revision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("fencingEpoch", receipt.fencingEpoch().map(Object::toString).orElse(""));
        fields.put("lifecycleStatus", receipt.lifecycleStatus().map(RouteLifecycleStatus::name).orElse(""));
        fields.put("idempotencyKey", receipt.idempotencyKey().orElse(""));
        fields.put("commandId", receipt.commandId().orElse(""));
        return lines(fields);
    }

    static RouteReceipt decodeReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        return new RouteReceipt(
                RouteReceiptStatus.valueOf(required(fields, "status")),
                optional(fields, "reason"),
                optional(fields, "routeId").map(RouteId::new),
                optionalSubjectId(fields, "subjectId"),
                optionalRevision(fields, "revision"),
                optionalLong(fields, "fencingEpoch"),
                optional(fields, "lifecycleStatus").map(RouteLifecycleStatus::valueOf),
                optional(fields, "idempotencyKey"),
                optional(fields, "commandId"));
    }

    static String encodeStoredDecision(StoredAuthorityDecision<RouteState, RouteReceipt> stored) {
        Objects.requireNonNull(stored, "stored");
        AuthorityDecision<RouteState, RouteReceipt> decision = stored.decision();
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

    static StoredAuthorityDecision<RouteState, RouteReceipt> decodeStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecisionStatus status = AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus"));
        Optional<AuthorityRejectionReason> rejectionReason =
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf);
        AuthorityDecision<RouteState, RouteReceipt> decision = new AuthorityDecision<>(
                status,
                rejectionReason,
                new Revision(longValue(fields, "revision")),
                decodeState(unprefixed(fields, "state.")),
                decodeReceipt(unprefixed(fields, "response.")),
                List.of(),
                decodeTrace(fields),
                Boolean.parseBoolean(required(fields, "replayed")));
        return new StoredAuthorityDecision<>(required(fields, "payloadFingerprint"), decision);
    }

    static String encodeDecisionPayload(AuthorityDecision<RouteState, RouteReceipt> decision) {
        return encodeStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    private static RouteCommand decodePayload(Map<String, String> fields) {
        String commandName = required(fields, "commandName");
        RouteId routeId = new RouteId(required(fields, "routeId"));
        return switch (commandName) {
            case OPEN_COMMAND -> new OpenRoute(
                    routeId,
                    subjectId(required(fields, "subjectId")),
                    new SessionId(required(fields, "targetSessionId")),
                    new InstanceId(required(fields, "targetInstanceId")),
                    instant(fields, "requestedAt"),
                    instant(fields, "expiresAt"));
            case ACKNOWLEDGE_COMMAND -> new AcknowledgeRoute(
                    routeId,
                    subjectId(required(fields, "subjectId")),
                    new SessionId(required(fields, "targetSessionId")),
                    new InstanceId(required(fields, "targetInstanceId")),
                    instant(fields, "acknowledgedAt"));
            case TIMEOUT_COMMAND -> new TimeoutRoute(routeId, instant(fields, "timedOutAt"));
            default -> throw new IllegalArgumentException("Unsupported Route command " + commandName);
        };
    }

    private static void encodePayload(Map<String, String> fields, RouteCommand payload) {
        fields.put("routeId", payload.routeId().value());
        if (payload instanceof OpenRoute open) {
            fields.put("subjectId", open.subjectId().value().toString());
            fields.put("targetSessionId", open.targetSessionId().value());
            fields.put("targetInstanceId", open.targetInstanceId().value());
            fields.put("requestedAt", open.requestedAt().toString());
            fields.put("expiresAt", open.expiresAt().toString());
            return;
        }
        if (payload instanceof AcknowledgeRoute acknowledge) {
            fields.put("subjectId", acknowledge.subjectId().value().toString());
            fields.put("targetSessionId", acknowledge.targetSessionId().value());
            fields.put("targetInstanceId", acknowledge.targetInstanceId().value());
            fields.put("acknowledgedAt", acknowledge.acknowledgedAt().toString());
            return;
        }
        if (payload instanceof TimeoutRoute timeout) {
            fields.put("timedOutAt", timeout.timedOutAt().toString());
            return;
        }
        throw new IllegalArgumentException("Unsupported Route payload " + payload.getClass().getName());
    }

    private static void encodeSnapshot(Map<String, String> fields, String prefix, RouteSnapshot snapshot) {
        fields.put(prefix + "routeId", snapshot.routeId().value());
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "targetSessionId", snapshot.targetSessionId().value());
        fields.put(prefix + "targetInstanceId", snapshot.targetInstanceId().value());
        fields.put(prefix + "status", snapshot.status().name());
        fields.put(prefix + "requestedAt", snapshot.requestedAt().toString());
        fields.put(prefix + "expiresAt", snapshot.expiresAt().toString());
        fields.put(prefix + "completedAt", snapshot.completedAt().map(Instant::toString).orElse(""));
    }

    private static RouteSnapshot decodeSnapshot(Map<String, String> fields, String prefix) {
        return new RouteSnapshot(
                new RouteId(required(fields, prefix + "routeId")),
                subjectId(required(fields, prefix + "subjectId")),
                new SessionId(required(fields, prefix + "targetSessionId")),
                new InstanceId(required(fields, prefix + "targetInstanceId")),
                RouteLifecycleStatus.valueOf(required(fields, prefix + "status")),
                instant(fields, prefix + "requestedAt"),
                instant(fields, prefix + "expiresAt"),
                optionalInstant(fields, prefix + "completedAt"));
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
                traceCreatedAt(fields),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static Instant traceCreatedAt(Map<String, String> fields) {
        return optionalInstant(fields, "traceCreatedAt")
                .or(() -> optionalInstant(fields, "requestedAt"))
                .or(() -> optionalInstant(fields, "acknowledgedAt"))
                .or(() -> optionalInstant(fields, "timedOutAt"))
                .orElseGet(() -> instant(fields, "receivedAt"));
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
                throw new IllegalArgumentException("Malformed Route authority wire line: " + line);
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

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Route authority wire field " + key);
        }
        return value;
    }

    private static String firstRequired(Map<String, String> fields, String first, String second) {
        return optional(fields, first).or(() -> optional(fields, second))
                .orElseThrow(() -> new IllegalArgumentException("Missing Route authority wire field " + first));
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
        return optional(fields, key).map(RouteAuthorityWireCodec::subjectId);
    }

    private static SubjectId subjectId(String value) {
        return new SubjectId(UUID.fromString(value));
    }
}
