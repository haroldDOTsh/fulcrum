package sh.harold.fulcrum.validation.auctionescrow;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

final class AuctionEscrowCommandWireCodec {
    private AuctionEscrowCommandWireCodec() {
    }

    static String encode(AuthorityCommand<AuctionEscrowCommand> command) {
        Objects.requireNonNull(command, "command");
        AuctionEscrowCommand payload = command.envelope().payload();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("commandId", command.envelope().commandId().value());
        fields.put("idempotencyKey", command.envelope().idempotencyKey().value());
        fields.put("principalId", command.envelope().principalId().value());
        fields.put("aggregateId", command.envelope().aggregateId().value());
        fields.put("fencingEpoch", Long.toString(command.fencingEpoch()));
        fields.put("expectedRevision", command.expectedRevision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", command.payloadFingerprint());
        fields.put("receivedAt", command.receivedAt().toString());
        fields.put("traceId", command.envelope().traceEnvelope().traceId());
        fields.put("spanId", command.envelope().traceEnvelope().spanId());
        fields.put("originService", command.envelope().traceEnvelope().originService());
        fields.put("originInstanceId", command.envelope().traceEnvelope().originInstanceId().value());
        fields.put("auctionId", payload.auctionId());
        if (payload instanceof OpenEscrow open) {
            fields.put("type", "OPEN");
            fields.put("sellerId", open.sellerId());
            fields.put("itemRef", open.itemRef());
            fields.put("currency", open.currency());
            fields.put("occurredAt", open.openedAt().toString());
        } else if (payload instanceof PlaceHold hold) {
            fields.put("type", "HOLD");
            fields.put("bidderId", hold.bidderId());
            fields.put("amountMinor", Long.toString(hold.amountMinor()));
            fields.put("currency", hold.currency());
            fields.put("occurredAt", hold.heldAt().toString());
        } else if (payload instanceof SettleEscrow settle) {
            fields.put("type", "SETTLE");
            fields.put("occurredAt", settle.settledAt().toString());
        } else if (payload instanceof CancelEscrow cancel) {
            fields.put("type", "CANCEL");
            fields.put("reason", cancel.reason());
            fields.put("occurredAt", cancel.cancelledAt().toString());
        } else {
            throw new IllegalArgumentException("unknown escrow command payload");
        }
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    static AuthorityCommand<AuctionEscrowCommand> decode(ConsumerRecord<String, String> record) {
        Objects.requireNonNull(record, "record");
        Map<String, String> fields = parse(record.value());
        AuctionEscrowCommand payload = switch (required(fields, "type")) {
            case "OPEN" -> new OpenEscrow(
                    required(fields, "auctionId"),
                    required(fields, "sellerId"),
                    required(fields, "itemRef"),
                    required(fields, "currency"),
                    Instant.parse(required(fields, "occurredAt")));
            case "HOLD" -> new PlaceHold(
                    required(fields, "auctionId"),
                    required(fields, "bidderId"),
                    Long.parseLong(required(fields, "amountMinor")),
                    required(fields, "currency"),
                    Instant.parse(required(fields, "occurredAt")));
            case "SETTLE" -> new SettleEscrow(
                    required(fields, "auctionId"),
                    Instant.parse(required(fields, "occurredAt")));
            case "CANCEL" -> new CancelEscrow(
                    required(fields, "auctionId"),
                    required(fields, "reason"),
                    Instant.parse(required(fields, "occurredAt")));
            default -> throw new IllegalArgumentException("unknown escrow command type: " + required(fields, "type"));
        };
        PrincipalId principalId = new PrincipalId(required(fields, "principalId"));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        principalId,
                        new AggregateId(record.key() == null || record.key().isBlank()
                                ? required(fields, "aggregateId")
                                : record.key()),
                        AuctionEscrowAuthority.CONTRACT,
                        AuctionEscrowContract.commandName(payload),
                        trace(fields),
                        Optional.empty(),
                        payload),
                principalId,
                Long.parseLong(required(fields, "fencingEpoch")),
                optionalRevision(fields.get("expectedRevision")),
                required(fields, "payloadFingerprint"),
                Instant.parse(required(fields, "receivedAt")));
    }

    private static TraceEnvelope trace(Map<String, String> fields) {
        return new TraceEnvelope(
                required(fields, "traceId"),
                required(fields, "spanId"),
                Optional.empty(),
                Instant.parse(required(fields, "receivedAt")),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static Optional<Revision> optionalRevision(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Revision(Long.parseLong(value)));
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

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing escrow command field " + name);
        }
        return value;
    }
}

