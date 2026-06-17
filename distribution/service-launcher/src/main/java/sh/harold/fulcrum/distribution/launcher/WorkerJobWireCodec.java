package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.host.worker.WorkerJobDecisionStatus;
import sh.harold.fulcrum.host.worker.WorkerJobId;
import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;
import sh.harold.fulcrum.host.worker.WorkerJobRejectionReason;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;
import sh.harold.fulcrum.host.worker.WorkerJobResult;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;

final class WorkerJobWireCodec {
    private WorkerJobWireCodec() {
    }

    static WorkerJobRequest decodeRequest(ConsumerRecord<String, String> record) {
        Objects.requireNonNull(record, "record");
        Map<String, String> fields = fields(record.value());
        return new WorkerJobRequest(
                new WorkerJobId(optional(fields, "jobId").orElse(record.key())),
                jobKind(required(fields, "jobKind")),
                required(fields, "workKey"),
                new IdempotencyKey(required(fields, "idempotencyKey")),
                required(fields, "payloadFingerprint"),
                new ResolvedManifestId(required(fields, "resolvedManifestId")),
                decodeTrace(fields),
                instant(fields, "enqueuedAt"),
                optionalInstant(fields, "deadlineAt"));
    }

    static String encodeRequest(WorkerJobRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobId", request.jobId().value());
        fields.put("jobKind", WorkerJobObjectHandler.workerDomain(request.jobKind()));
        fields.put("workKey", request.workKey());
        fields.put("idempotencyKey", request.idempotencyKey().value());
        fields.put("payloadFingerprint", request.payloadFingerprint());
        fields.put("resolvedManifestId", request.resolvedManifestId().value());
        encodeTrace(fields, request.traceEnvelope());
        fields.put("enqueuedAt", request.enqueuedAt().toString());
        fields.put("deadlineAt", request.deadlineAt().map(Instant::toString).orElse(""));
        return lines(fields);
    }

    static WorkerJobReceipt decodeReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        return new WorkerJobReceipt(
                WorkerJobDecisionStatus.valueOf(required(fields, "status")),
                Boolean.parseBoolean(required(fields, "accepted")),
                new WorkerJobId(required(fields, "jobId")),
                jobKind(required(fields, "jobKind")),
                required(fields, "workKey"),
                new IdempotencyKey(required(fields, "idempotencyKey")),
                new ResolvedManifestId(required(fields, "resolvedManifestId")),
                new InstanceId(required(fields, "workerInstanceId")),
                Duration.ofMillis(longValue(fields, "observedLagMillis")),
                optional(fields, "resultCode").map(resultCode -> new WorkerJobResult(
                        resultCode,
                        required(fields, "outputRef"))),
                optional(fields, "rejectionReason").map(WorkerJobRejectionReason::valueOf),
                decodeTrace(fields));
    }

    static String encodeReceipt(WorkerJobReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", receipt.status().name());
        fields.put("accepted", Boolean.toString(receipt.accepted()));
        fields.put("jobId", receipt.jobId().value());
        fields.put("jobKind", WorkerJobObjectHandler.workerDomain(receipt.jobKind()));
        fields.put("workKey", receipt.workKey());
        fields.put("idempotencyKey", receipt.idempotencyKey().value());
        fields.put("resolvedManifestId", receipt.resolvedManifestId().value());
        fields.put("workerInstanceId", receipt.workerInstanceId().value());
        fields.put("observedLagMillis", Long.toString(receipt.observedLag().toMillis()));
        fields.put("resultCode", receipt.result().map(WorkerJobResult::resultCode).orElse(""));
        fields.put("outputRef", receipt.result().map(WorkerJobResult::outputRef).orElse(""));
        fields.put("rejectionReason", receipt.rejectionReason().map(Enum::name).orElse(""));
        encodeTrace(fields, receipt.traceEnvelope());
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
                instant(fields, "traceCreatedAt"),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static WorkerJobKind jobKind(String value) {
        return WorkerJobKind.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed worker job wire line: " + line);
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
            throw new IllegalArgumentException("Missing worker job wire field " + key);
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

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }

    private static Optional<Instant> optionalInstant(Map<String, String> fields, String key) {
        return optional(fields, key).map(Instant::parse);
    }
}
