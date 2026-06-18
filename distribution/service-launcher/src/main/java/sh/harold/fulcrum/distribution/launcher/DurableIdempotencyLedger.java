package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class DurableIdempotencyLedger {
    static final String CONTROL_RECORD_TYPE = "control-idempotency";
    static final String WORKER_RECORD_TYPE = "worker-idempotency";

    private final String recordType;
    private final Map<String, StoredDecision> decisions = new LinkedHashMap<>();

    private DurableIdempotencyLedger(String recordType) {
        this.recordType = requireNonBlank(recordType, "recordType");
    }

    static DurableIdempotencyLedger replay(
            KafkaClientBundle kafka,
            String topic,
            Duration pollTimeout,
            String recordType) {
        DurableIdempotencyLedger ledger = new DurableIdempotencyLedger(recordType);
        KafkaStateTopicReplayer.replay(kafka, topic, pollTimeout, ledger::replayRecord);
        return ledger;
    }

    Optional<StoredDecision> lookup(String idempotencyKey) {
        return Optional.ofNullable(decisions.get(requireNonBlank(idempotencyKey, "idempotencyKey")));
    }

    void put(StoredDecision decision) {
        decisions.put(decision.idempotencyKey(), decision);
    }

    String encode(StoredDecision decision) {
        Objects.requireNonNull(decision, "decision");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("recordType", recordType);
        fields.put("idempotencyKey", decision.idempotencyKey());
        fields.put("payloadFingerprint", encodeField(decision.payloadFingerprint()));
        fields.put("responseKey", encodeField(decision.responseKey()));
        fields.put("responseValue", encodeField(decision.responseValue()));
        return lines(fields);
    }

    private void replayRecord(ConsumerRecord<String, String> record) {
        decode(record.value(), recordType).ifPresent(this::put);
    }

    private static Optional<StoredDecision> decode(String payload, String expectedRecordType) {
        Map<String, String> fields = fields(payload);
        if (!expectedRecordType.equals(fields.get("recordType"))) {
            return Optional.empty();
        }
        return Optional.of(new StoredDecision(
                required(fields, "idempotencyKey"),
                decodeField(required(fields, "payloadFingerprint")),
                decodeField(required(fields, "responseKey")),
                decodeField(required(fields, "responseValue"))));
    }

    private static String encodeField(String value) {
        return Base64.getEncoder().encodeToString(
                requireNonBlank(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        return new String(Base64.getDecoder().decode(requireNonBlank(value, "value")), StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed idempotency ledger wire line: " + line);
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
            throw new IllegalArgumentException("Missing idempotency ledger wire field " + key);
        }
        return value;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    record StoredDecision(
            String idempotencyKey,
            String payloadFingerprint,
            String responseKey,
            String responseValue) {
        StoredDecision {
            idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
            payloadFingerprint = requireNonBlank(payloadFingerprint, "payloadFingerprint");
            responseKey = requireNonBlank(responseKey, "responseKey");
            responseValue = requireNonBlank(responseValue, "responseValue");
        }
    }
}
