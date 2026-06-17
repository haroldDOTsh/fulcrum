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
import sh.harold.fulcrum.data.artifact.ArtifactDigest;
import sh.harold.fulcrum.data.artifact.ArtifactKind;
import sh.harold.fulcrum.data.artifact.ArtifactMetadata;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceipt;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceiptStatus;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataState;
import sh.harold.fulcrum.data.artifact.ContentAddress;
import sh.harold.fulcrum.data.artifact.ProvenanceRef;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ArtifactMetadataAuthorityWireCodec {
    static final String CONTRACT = "artifact-metadata";
    static final String PUBLISH_COMMAND = "publish-artifact-metadata";

    private ArtifactMetadataAuthorityWireCodec() {
    }

    static AuthorityCommand<PublishArtifactMetadata> decodeCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        PublishArtifactMetadata payload = decodePayload(fields);
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

    static String encodeCommand(AuthorityCommand<PublishArtifactMetadata> command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        CommandEnvelope<PublishArtifactMetadata> envelope = command.envelope();
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

    static String encodeState(ArtifactMetadataState state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("metadata", Boolean.toString(state.metadata().isPresent()));
        state.metadata().ifPresent(metadata -> encodeMetadata(fields, "", metadata));
        return lines(fields);
    }

    static ArtifactMetadataState decodeState(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "metadata"))) {
            return new ArtifactMetadataState(Optional.empty());
        }
        return new ArtifactMetadataState(decodeMetadata(fields, ""));
    }

    static String encodeReceipt(ArtifactMetadataReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", receipt.status().name());
        fields.put("reason", receipt.rejectionReason().orElse(""));
        fields.put("digestAlgorithm", receipt.digest().map(ArtifactDigest::algorithm).orElse(""));
        fields.put("digestValue", receipt.digest().map(ArtifactDigest::value).orElse(""));
        fields.put("revision", receipt.revision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("fencingEpoch", receipt.fencingEpoch().map(Object::toString).orElse(""));
        fields.put("idempotencyKey", receipt.idempotencyKey().orElse(""));
        fields.put("commandId", receipt.commandId().orElse(""));
        return lines(fields);
    }

    static ArtifactMetadataReceipt decodeReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        Optional<ArtifactDigest> digest = optional(fields, "digestAlgorithm")
                .map(algorithm -> new ArtifactDigest(algorithm, required(fields, "digestValue")));
        return new ArtifactMetadataReceipt(
                ArtifactMetadataReceiptStatus.valueOf(required(fields, "status")),
                optional(fields, "reason"),
                digest,
                optionalRevision(fields, "revision"),
                optionalLong(fields, "fencingEpoch"),
                optional(fields, "idempotencyKey"),
                optional(fields, "commandId"));
    }

    static String encodeStoredDecision(
            StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> stored) {
        Objects.requireNonNull(stored, "stored");
        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = stored.decision();
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

    static StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decodeStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecisionStatus status = AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus"));
        Optional<AuthorityRejectionReason> rejectionReason =
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf);
        AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = new AuthorityDecision<>(
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

    static String encodeDecisionPayload(AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision) {
        return encodeStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    private static PublishArtifactMetadata decodePayload(Map<String, String> fields) {
        String commandName = required(fields, "commandName");
        if (!PUBLISH_COMMAND.equals(commandName)) {
            throw new IllegalArgumentException("Unsupported artifact metadata command " + commandName);
        }
        return new PublishArtifactMetadata(
                new ArtifactDigest(required(fields, "digestAlgorithm"), required(fields, "digestValue")),
                ArtifactKind.valueOf(required(fields, "kind")),
                longValue(fields, "byteLength"),
                new ContentAddress(required(fields, "contentAddress")),
                new ProvenanceRef(required(fields, "provenance")));
    }

    private static void encodePayload(Map<String, String> fields, PublishArtifactMetadata payload) {
        fields.put("digestAlgorithm", payload.digest().algorithm());
        fields.put("digestValue", payload.digest().value());
        fields.put("kind", payload.kind().name());
        fields.put("byteLength", Long.toString(payload.byteLength()));
        fields.put("contentAddress", payload.contentAddress().value());
        fields.put("provenance", payload.provenance().value());
    }

    private static void encodeMetadata(Map<String, String> fields, String prefix, ArtifactMetadata metadata) {
        fields.put(prefix + "digestAlgorithm", metadata.digest().algorithm());
        fields.put(prefix + "digestValue", metadata.digest().value());
        fields.put(prefix + "kind", metadata.kind().name());
        fields.put(prefix + "byteLength", Long.toString(metadata.byteLength()));
        fields.put(prefix + "contentAddress", metadata.contentAddress().value());
        fields.put(prefix + "producerPrincipal", metadata.producerPrincipal().value());
        fields.put(prefix + "provenance", metadata.provenance().value());
        fields.put(prefix + "publishedAt", metadata.publishedAt().toString());
    }

    private static ArtifactMetadata decodeMetadata(Map<String, String> fields, String prefix) {
        return new ArtifactMetadata(
                new ArtifactDigest(required(fields, prefix + "digestAlgorithm"), required(fields, prefix + "digestValue")),
                ArtifactKind.valueOf(required(fields, prefix + "kind")),
                longValue(fields, prefix + "byteLength"),
                new ContentAddress(required(fields, prefix + "contentAddress")),
                new PrincipalId(required(fields, prefix + "producerPrincipal")),
                new ProvenanceRef(required(fields, prefix + "provenance")),
                instant(fields, prefix + "publishedAt"));
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
                optionalInstant(fields, "traceCreatedAt").orElseGet(() -> instant(fields, "receivedAt")),
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
                throw new IllegalArgumentException("Malformed artifact metadata authority wire line: " + line);
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
            throw new IllegalArgumentException("Missing artifact metadata authority wire field " + key);
        }
        return value;
    }

    private static String firstRequired(Map<String, String> fields, String first, String second) {
        return optional(fields, first).or(() -> optional(fields, second))
                .orElseThrow(() -> new IllegalArgumentException("Missing artifact metadata authority wire field " + first));
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
