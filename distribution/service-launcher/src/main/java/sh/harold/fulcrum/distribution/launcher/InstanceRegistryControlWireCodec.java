package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.instance.ControlInstanceNames;
import sh.harold.fulcrum.control.instance.InstanceRegistryControlCommand;
import sh.harold.fulcrum.control.instance.RegisterInstance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class InstanceRegistryControlWireCodec {
    private InstanceRegistryControlWireCodec() {
    }

    static InstanceRegistryControlCommand<RegisterInstance> decodeRegisterCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        if (!ControlInstanceNames.REGISTER.value().equals(required(fields, "commandName"))) {
            throw new IllegalArgumentException("Unsupported instance-registry control command " + required(fields, "commandName"));
        }
        TraceEnvelope trace = decodeTrace(fields);
        InstanceId instanceId = new InstanceId(required(fields, "instanceId"));
        RegisterInstance payload = new RegisterInstance(
                instanceId,
                required(fields, "instanceKind"),
                new PoolId(required(fields, "poolId")),
                new MachineRef(required(fields, "machineRef")),
                new PrincipalId(required(fields, "instancePrincipalId")),
                instant(fields, "registeredAt"),
                trace);
        return new InstanceRegistryControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        new PrincipalId(firstRequired(fields, "principalId", "declaredPrincipalId")),
                        new AggregateId(optional(fields, "aggregateId").orElse(record.key())),
                        ControlInstanceNames.CONTRACT,
                        ControlInstanceNames.REGISTER,
                        trace,
                        optionalInstant(fields, "deadlineAt"),
                        payload),
                new PrincipalId(firstRequired(fields, "authenticatedPrincipal", "authenticatedPrincipalId")),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeRegisterCommand(InstanceRegistryControlCommand<RegisterInstance> command) {
        Objects.requireNonNull(command, "command");
        RegisterInstance payload = command.envelope().payload();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("commandId", command.envelope().commandId().value());
        fields.put("idempotencyKey", command.envelope().idempotencyKey().value());
        fields.put("principalId", command.envelope().principalId().value());
        fields.put("aggregateId", command.envelope().aggregateId().value());
        fields.put("commandName", command.envelope().commandName().value());
        encodeTrace(fields, command.envelope().traceEnvelope());
        fields.put("deadlineAt", command.envelope().deadlineAt().map(Instant::toString).orElse(""));
        fields.put("authenticatedPrincipal", command.authenticatedPrincipal().value());
        fields.put("fencingEpoch", Long.toString(command.fencingEpoch()));
        fields.put("expectedRevision", command.expectedRevision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", command.payloadFingerprint());
        fields.put("receivedAt", command.receivedAt().toString());
        fields.put("instanceId", payload.instanceId().value());
        fields.put("instanceKind", payload.instanceKind());
        fields.put("poolId", payload.poolId().value());
        fields.put("machineRef", payload.machineRef().value());
        fields.put("instancePrincipalId", payload.instancePrincipalId().value());
        fields.put("registeredAt", payload.registeredAt().toString());
        return lines(fields);
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
                optionalInstant(fields, "traceCreatedAt").orElseGet(() -> instant(fields, "registeredAt")),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed instance-registry control wire line: " + line);
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
            throw new IllegalArgumentException("Missing instance-registry control wire field " + key);
        }
        return value;
    }

    private static String firstRequired(Map<String, String> fields, String first, String second) {
        return optional(fields, first).or(() -> optional(fields, second))
                .orElseThrow(() -> new IllegalArgumentException("Missing instance-registry control wire field " + first));
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
}
