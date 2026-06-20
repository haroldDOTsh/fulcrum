package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

final class AuctionEscrowStateStoreCodec implements JdbcAuthorityStateCodec<AuctionEscrowState> {
    static final AuctionEscrowStateStoreCodec INSTANCE = new AuctionEscrowStateStoreCodec();

    private AuctionEscrowStateStoreCodec() {
    }

    @Override
    public String encode(AuctionEscrowState state) {
        AuctionEscrowState checked = Objects.requireNonNull(state, "state");
        Map<String, String> fields = new LinkedHashMap<>();
        if (checked.current().isEmpty()) {
            fields.put("empty", "true");
            return encodeFields(fields);
        }
        EscrowSnapshot snapshot = checked.current().orElseThrow();
        fields.put("empty", "false");
        fields.put("auctionId", encoded(snapshot.auctionId()));
        fields.put("sellerId", encoded(snapshot.sellerId()));
        fields.put("itemRef", encoded(snapshot.itemRef()));
        fields.put("currency", encoded(snapshot.currency()));
        fields.put("status", snapshot.status().name());
        fields.put("updatedAt", snapshot.updatedAt().toString());
        fields.put("holds", snapshot.holds().stream()
                .map(AuctionEscrowStateStoreCodec::encodeHold)
                .collect(Collectors.joining(",")));
        snapshot.releasePlan().ifPresentOrElse(
                plan -> {
                    fields.put("releaseTerminalStatus", plan.terminalStatus().name());
                    fields.put("releaseTotalHeld", Long.toString(plan.totalHeldMinor()));
                    fields.put("releaseTotalPayout", Long.toString(plan.totalPayoutMinor()));
                    fields.put("releaseTotalRefunded", Long.toString(plan.totalRefundedMinor()));
                    fields.put("releaseFingerprint", plan.fingerprint());
                    fields.put("releaseLines", plan.lines().stream()
                            .map(AuctionEscrowStateStoreCodec::encodeReleaseLine)
                            .collect(Collectors.joining(",")));
                },
                () -> fields.put("releaseTerminalStatus", ""));
        return encodeFields(fields);
    }

    @Override
    public AuctionEscrowState decode(String payload) {
        Map<String, String> fields = parse(payload);
        if (Boolean.parseBoolean(fields.getOrDefault("empty", "false"))) {
            return AuctionEscrowState.empty();
        }
        List<EscrowHold> holds = decodeHolds(fields.getOrDefault("holds", ""));
        Optional<ReleasePlan> releasePlan = decodeReleasePlan(fields);
        return new AuctionEscrowState(Optional.of(new EscrowSnapshot(
                decoded(required(fields, "auctionId")),
                decoded(required(fields, "sellerId")),
                decoded(required(fields, "itemRef")),
                decoded(required(fields, "currency")),
                EscrowStatus.valueOf(required(fields, "status")),
                holds,
                releasePlan,
                Instant.parse(required(fields, "updatedAt")))));
    }

    private static String encodeFields(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
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

    private static String encodeHold(EscrowHold hold) {
        return hold.sequence()
                + "~" + encoded(hold.bidderId())
                + "~" + hold.amountMinor()
                + "~" + encoded(hold.currency())
                + "~" + hold.heldAt();
    }

    private static List<EscrowHold> decodeHolds(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        List<EscrowHold> holds = new ArrayList<>();
        for (String encodedHold : payload.split(",", -1)) {
            String[] fields = encodedHold.split("~", -1);
            holds.add(new EscrowHold(
                    Long.parseLong(fields[0]),
                    decoded(fields[1]),
                    Long.parseLong(fields[2]),
                    decoded(fields[3]),
                    Instant.parse(fields[4])));
        }
        return holds;
    }

    private static Optional<ReleasePlan> decodeReleasePlan(Map<String, String> fields) {
        String terminalStatus = fields.get("releaseTerminalStatus");
        if (terminalStatus == null || terminalStatus.isBlank()) {
            return Optional.empty();
        }
        List<ReleaseLine> lines = new ArrayList<>();
        String encodedLines = fields.getOrDefault("releaseLines", "");
        if (!encodedLines.isBlank()) {
            for (String encodedLine : encodedLines.split(",", -1)) {
                String[] lineFields = encodedLine.split("~", -1);
                lines.add(new ReleaseLine(
                        ReleaseLineKind.valueOf(lineFields[0]),
                        decoded(lineFields[1]),
                        Long.parseLong(lineFields[2]),
                        decoded(lineFields[3]),
                        Long.parseLong(lineFields[4])));
            }
        }
        return Optional.of(new ReleasePlan(
                EscrowStatus.valueOf(terminalStatus),
                lines,
                Long.parseLong(required(fields, "releaseTotalHeld")),
                Long.parseLong(required(fields, "releaseTotalPayout")),
                Long.parseLong(required(fields, "releaseTotalRefunded")),
                required(fields, "releaseFingerprint")));
    }

    private static String encodeReleaseLine(ReleaseLine line) {
        return line.kind()
                + "~" + encoded(line.recipientId())
                + "~" + line.amountMinor()
                + "~" + encoded(line.currency())
                + "~" + line.sourceHoldSequence();
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(EscrowNames.requireNonBlank(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing escrow state field " + name);
        }
        return value;
    }
}
