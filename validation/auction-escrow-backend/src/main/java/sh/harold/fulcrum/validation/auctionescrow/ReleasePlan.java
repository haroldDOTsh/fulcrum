package sh.harold.fulcrum.validation.auctionescrow;

import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public record ReleasePlan(
        EscrowStatus terminalStatus,
        List<ReleaseLine> lines,
        long totalHeldMinor,
        long totalPayoutMinor,
        long totalRefundedMinor,
        String fingerprint) {
    public ReleasePlan {
        terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus == EscrowStatus.OPEN) {
            throw new IllegalArgumentException("release plan must be terminal");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (totalHeldMinor < 0 || totalPayoutMinor < 0 || totalRefundedMinor < 0) {
            throw new IllegalArgumentException("totals must be non-negative");
        }
        if (totalHeldMinor != totalPayoutMinor + totalRefundedMinor) {
            throw new IllegalArgumentException("release plan must conserve held value");
        }
        fingerprint = EscrowNames.requireNonBlank(fingerprint, "fingerprint");
    }

    static ReleasePlan settle(EscrowSnapshot snapshot) {
        EscrowHold winner = snapshot.holds().stream()
                .max(Comparator.comparingLong(EscrowHold::amountMinor)
                        .thenComparing(Comparator.comparingLong(EscrowHold::sequence).reversed()))
                .orElseThrow(() -> new IllegalStateException("cannot settle escrow without holds"));
        List<ReleaseLine> lines = snapshot.holds().stream()
                .sorted(Comparator.comparingLong(EscrowHold::sequence))
                .map(hold -> hold.equals(winner)
                        ? new ReleaseLine(ReleaseLineKind.WINNER_PAYOUT, snapshot.sellerId(), hold.amountMinor(), hold.currency(), hold.sequence())
                        : new ReleaseLine(ReleaseLineKind.LOSER_REFUND, hold.bidderId(), hold.amountMinor(), hold.currency(), hold.sequence()))
                .toList();
        return from(EscrowStatus.SETTLED, snapshot, lines);
    }

    static ReleasePlan cancel(EscrowSnapshot snapshot) {
        List<ReleaseLine> lines = snapshot.holds().stream()
                .sorted(Comparator.comparingLong(EscrowHold::sequence))
                .map(hold -> new ReleaseLine(ReleaseLineKind.CANCEL_REFUND, hold.bidderId(), hold.amountMinor(), hold.currency(), hold.sequence()))
                .toList();
        return from(EscrowStatus.CANCELLED, snapshot, lines);
    }

    String wireValue() {
        return "terminalStatus=" + terminalStatus
                + "|totalHeld=" + totalHeldMinor
                + "|totalPayout=" + totalPayoutMinor
                + "|totalRefunded=" + totalRefundedMinor
                + "|fingerprint=" + fingerprint
                + "|lines=" + lines.stream().map(ReleaseLine::wireValue).collect(Collectors.joining(","));
    }

    private static ReleasePlan from(EscrowStatus terminalStatus, EscrowSnapshot snapshot, List<ReleaseLine> lines) {
        long totalHeld = snapshot.holds().stream().mapToLong(EscrowHold::amountMinor).sum();
        long totalPayout = lines.stream()
                .filter(line -> line.kind() == ReleaseLineKind.WINNER_PAYOUT)
                .mapToLong(ReleaseLine::amountMinor)
                .sum();
        long totalRefunded = lines.stream()
                .filter(line -> line.kind() != ReleaseLineKind.WINNER_PAYOUT)
                .mapToLong(ReleaseLine::amountMinor)
                .sum();
        String identity = terminalStatus + "|" + snapshot.auctionId() + "|"
                + lines.stream().map(ReleaseLine::wireValue).collect(Collectors.joining("|"));
        return new ReleasePlan(terminalStatus, lines, totalHeld, totalPayout, totalRefunded, sha256(identity));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
