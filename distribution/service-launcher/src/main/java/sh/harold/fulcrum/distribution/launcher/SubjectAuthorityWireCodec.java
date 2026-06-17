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
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.subject.RegisterSubject;
import sh.harold.fulcrum.data.subject.RetireSubject;
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectExternalIdentity;
import sh.harold.fulcrum.data.subject.SubjectIdentityProvider;
import sh.harold.fulcrum.data.subject.SubjectLifecycleStatus;
import sh.harold.fulcrum.data.subject.SubjectReceipt;
import sh.harold.fulcrum.data.subject.SubjectReceiptStatus;
import sh.harold.fulcrum.data.subject.SubjectRetireReason;
import sh.harold.fulcrum.data.subject.SubjectSnapshot;
import sh.harold.fulcrum.data.subject.SubjectState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class SubjectAuthorityWireCodec {
    static final String CONTRACT = "subject";
    static final String REGISTER_COMMAND = "register-subject";
    static final String RETIRE_COMMAND = "retire-subject";

    private SubjectAuthorityWireCodec() {
    }

    static AuthorityCommand<SubjectCommand> decodeCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        SubjectCommand payload = decodePayload(fields);
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        new PrincipalId(required(fields, "principalId")),
                        new AggregateId(optional(fields, "aggregateId").orElse(record.key())),
                        new ContractName(optional(fields, "contractName").orElse(CONTRACT)),
                        new CommandName(required(fields, "commandName")),
                        decodeTrace(fields),
                        optionalInstant(fields, "deadlineAt"),
                        payload),
                new PrincipalId(required(fields, "authenticatedPrincipal")),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeCommand(AuthorityCommand<SubjectCommand> command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        CommandEnvelope<SubjectCommand> envelope = command.envelope();
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

    static String encodeState(SubjectState state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("current", Boolean.toString(state.current().isPresent()));
        state.current().ifPresent(snapshot -> encodeSnapshot(fields, "", snapshot));
        return lines(fields);
    }

    static SubjectState decodeState(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "current"))) {
            return SubjectState.empty();
        }
        return new SubjectState(decodeSnapshot(fields, ""));
    }

    static String encodeReceipt(SubjectReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", receipt.status().name());
        fields.put("reason", receipt.rejectionReason().orElse(""));
        fields.put("subjectId", receipt.subjectId().map(value -> value.value().toString()).orElse(""));
        fields.put("revision", receipt.revision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("fencingEpoch", receipt.fencingEpoch().map(Object::toString).orElse(""));
        fields.put("lifecycleStatus", receipt.lifecycleStatus().map(SubjectLifecycleStatus::name).orElse(""));
        fields.put("idempotencyKey", receipt.idempotencyKey().orElse(""));
        fields.put("commandId", receipt.commandId().orElse(""));
        return lines(fields);
    }

    static SubjectReceipt decodeReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        return new SubjectReceipt(
                SubjectReceiptStatus.valueOf(required(fields, "status")),
                optional(fields, "reason"),
                optionalSubjectId(fields, "subjectId"),
                optionalRevision(fields, "revision"),
                optionalLong(fields, "fencingEpoch"),
                optional(fields, "lifecycleStatus").map(SubjectLifecycleStatus::valueOf),
                optional(fields, "idempotencyKey"),
                optional(fields, "commandId"));
    }

    static String encodeStoredDecision(StoredAuthorityDecision<SubjectState, SubjectReceipt> stored) {
        Objects.requireNonNull(stored, "stored");
        AuthorityDecision<SubjectState, SubjectReceipt> decision = stored.decision();
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

    static StoredAuthorityDecision<SubjectState, SubjectReceipt> decodeStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecisionStatus status = AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus"));
        Optional<AuthorityRejectionReason> rejectionReason =
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf);
        AuthorityDecision<SubjectState, SubjectReceipt> decision = new AuthorityDecision<>(
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

    static String encodeDecisionPayload(AuthorityDecision<SubjectState, SubjectReceipt> decision) {
        return encodeStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    private static SubjectCommand decodePayload(Map<String, String> fields) {
        String commandName = required(fields, "commandName");
        SubjectId subjectId = subjectId(required(fields, "subjectId"));
        return switch (commandName) {
            case REGISTER_COMMAND -> new RegisterSubject(
                    subjectId,
                    SubjectIdentityProvider.valueOf(required(fields, "identityProvider")),
                    new SubjectExternalIdentity(required(fields, "externalIdentity")),
                    instant(fields, "registeredAt"));
            case RETIRE_COMMAND -> new RetireSubject(
                    subjectId,
                    instant(fields, "retiredAt"),
                    SubjectRetireReason.valueOf(required(fields, "retireReason")));
            default -> throw new IllegalArgumentException("Unsupported Subject command " + commandName);
        };
    }

    private static void encodePayload(Map<String, String> fields, SubjectCommand payload) {
        if (payload instanceof RegisterSubject register) {
            fields.put("subjectId", register.subjectId().value().toString());
            fields.put("identityProvider", register.identityProvider().name());
            fields.put("externalIdentity", register.externalIdentity().value());
            fields.put("registeredAt", register.registeredAt().toString());
            return;
        }
        if (payload instanceof RetireSubject retire) {
            fields.put("subjectId", retire.subjectId().value().toString());
            fields.put("retiredAt", retire.retiredAt().toString());
            fields.put("retireReason", retire.reason().name());
            return;
        }
        throw new IllegalArgumentException("Unsupported Subject payload " + payload.getClass().getName());
    }

    private static void encodeSnapshot(Map<String, String> fields, String prefix, SubjectSnapshot snapshot) {
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "identityProvider", snapshot.identityProvider().name());
        fields.put(prefix + "externalIdentity", snapshot.externalIdentity().value());
        fields.put(prefix + "registeredBy", snapshot.registeredBy().value());
        fields.put(prefix + "status", snapshot.status().name());
        fields.put(prefix + "registeredAt", snapshot.registeredAt().toString());
        fields.put(prefix + "retiredBy", snapshot.retiredBy().map(PrincipalId::value).orElse(""));
        fields.put(prefix + "retiredAt", snapshot.retiredAt().map(Instant::toString).orElse(""));
        fields.put(prefix + "retireReason", snapshot.retireReason().map(SubjectRetireReason::name).orElse(""));
    }

    private static SubjectSnapshot decodeSnapshot(Map<String, String> fields, String prefix) {
        return new SubjectSnapshot(
                subjectId(required(fields, prefix + "subjectId")),
                SubjectIdentityProvider.valueOf(required(fields, prefix + "identityProvider")),
                new SubjectExternalIdentity(required(fields, prefix + "externalIdentity")),
                new PrincipalId(required(fields, prefix + "registeredBy")),
                SubjectLifecycleStatus.valueOf(required(fields, prefix + "status")),
                instant(fields, prefix + "registeredAt"),
                optional(fields, prefix + "retiredBy").map(PrincipalId::new),
                optionalInstant(fields, prefix + "retiredAt"),
                optional(fields, prefix + "retireReason").map(SubjectRetireReason::valueOf));
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
                throw new IllegalArgumentException("Malformed Subject authority wire line: " + line);
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
            throw new IllegalArgumentException("Missing Subject authority wire field " + key);
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
        return optional(fields, key).map(SubjectAuthorityWireCodec::subjectId);
    }

    private static SubjectId subjectId(String value) {
        return new SubjectId(UUID.fromString(value));
    }
}
