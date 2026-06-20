package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.store.valkey.ValkeyStoredAuthorityDecisionCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class AuctionEscrowStoredDecisionCodec
        implements ValkeyStoredAuthorityDecisionCodec<AuctionEscrowState, AuctionEscrowReceipt> {
    @Override
    public String encode(StoredAuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> stored) {
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> decision = stored.decision();
        AuctionEscrowReceipt receipt = decision.response();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payloadFingerprint", encoded(stored.payloadFingerprint()));
        fields.put("decisionStatus", decision.status().name());
        fields.put("decisionRejection", decision.rejectionReason().map(Enum::name).orElse(""));
        fields.put("revision", Long.toString(decision.revision().value()));
        fields.put("state", encoded(AuctionEscrowStateStoreCodec.INSTANCE.encode(decision.state())));
        fields.put("receiptStatus", receipt.status().name());
        fields.put("receiptAuctionId", encoded(receipt.auctionId().orElse("")));
        fields.put("receiptEscrowStatus", receipt.escrowStatus().map(Enum::name).orElse(""));
        fields.put("receiptRevision", receipt.revision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("receiptFencingEpoch", receipt.fencingEpoch().map(Object::toString).orElse(""));
        fields.put("receiptTotalHeld", receipt.totalHeldMinor().map(Object::toString).orElse(""));
        fields.put("receiptTotalReleased", receipt.totalReleasedMinor().map(Object::toString).orElse(""));
        fields.put("receiptReleasePlan", encoded(receipt.releasePlanFingerprint().orElse("")));
        fields.put("receiptRejection", encoded(receipt.rejectionReason().orElse("")));
        fields.put("traceId", encoded(decision.traceEnvelope().traceId()));
        fields.put("spanId", encoded(decision.traceEnvelope().spanId()));
        fields.put("createdAt", decision.traceEnvelope().createdAt().toString());
        fields.put("originService", encoded(decision.traceEnvelope().originService()));
        fields.put("originInstanceId", encoded(decision.traceEnvelope().originInstanceId().value()));
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public StoredAuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> decode(String payload) {
        Map<String, String> fields = parse(payload);
        AuctionEscrowState state = AuctionEscrowStateStoreCodec.INSTANCE.decode(decoded(required(fields, "state")));
        Revision revision = new Revision(Long.parseLong(required(fields, "revision")));
        AuctionEscrowReceipt receipt = receipt(fields);
        TraceEnvelope trace = new TraceEnvelope(
                decoded(required(fields, "traceId")),
                decoded(required(fields, "spanId")),
                Optional.empty(),
                Instant.parse(required(fields, "createdAt")),
                decoded(required(fields, "originService")),
                new InstanceId(decoded(required(fields, "originInstanceId"))));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> decision;
        if (AuthorityDecisionStatus.REJECTED.name().equals(required(fields, "decisionStatus"))) {
            decision = AuthorityDecision.rejected(
                    AuthorityRejectionReason.valueOf(required(fields, "decisionRejection")),
                    revision,
                    state,
                    receipt,
                    trace);
        } else {
            decision = AuthorityDecision.accepted(revision, state, receipt, List.of(), trace);
        }
        return new StoredAuthorityDecision<>(decoded(required(fields, "payloadFingerprint")), decision);
    }

    private static AuctionEscrowReceipt receipt(Map<String, String> fields) {
        return new AuctionEscrowReceipt(
                AuctionEscrowReceiptStatus.valueOf(required(fields, "receiptStatus")),
                optionalDecoded(fields.get("receiptAuctionId")),
                optionalEnum(fields.get("receiptEscrowStatus"), EscrowStatus.class),
                optionalRevision(fields.get("receiptRevision")),
                optionalLong(fields.get("receiptFencingEpoch")),
                optionalLong(fields.get("receiptTotalHeld")),
                optionalLong(fields.get("receiptTotalReleased")),
                optionalDecoded(fields.get("receiptReleasePlan")),
                optionalDecoded(fields.get("receiptRejection")));
    }

    private static Map<String, String> parse(String payload) {
        return payload.lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> parts.length == 2 ? parts[1] : "",
                        (left, ignored) -> left,
                        LinkedHashMap::new));
    }

    private static Optional<String> optionalDecoded(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String decoded = decoded(value);
        return decoded.isBlank() ? Optional.empty() : Optional.of(decoded);
    }

    private static Optional<Revision> optionalRevision(String value) {
        return optionalLong(value).map(Revision::new);
    }

    private static Optional<Long> optionalLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(String value, Class<E> enumType) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Enum.valueOf(enumType, value));
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing stored escrow decision field " + name);
        }
        return value;
    }
}

