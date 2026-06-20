package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public record EscrowSnapshot(
        String auctionId,
        String sellerId,
        String itemRef,
        String currency,
        EscrowStatus status,
        List<EscrowHold> holds,
        Optional<ReleasePlan> releasePlan,
        Instant updatedAt) {
    public EscrowSnapshot {
        auctionId = EscrowNames.requireNonBlank(auctionId, "auctionId");
        sellerId = EscrowNames.requireNonBlank(sellerId, "sellerId");
        itemRef = EscrowNames.requireNonBlank(itemRef, "itemRef");
        currency = EscrowNames.requireNonBlank(currency, "currency");
        status = Objects.requireNonNull(status, "status");
        holds = List.copyOf(Objects.requireNonNull(holds, "holds"));
        releasePlan = releasePlan == null ? Optional.empty() : releasePlan;
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == EscrowStatus.OPEN && releasePlan.isPresent()) {
            throw new IllegalArgumentException("open escrow cannot have release plan");
        }
        if (status != EscrowStatus.OPEN && releasePlan.isEmpty()) {
            throw new IllegalArgumentException("terminal escrow requires release plan");
        }
    }

    static EscrowSnapshot open(OpenEscrow command) {
        return new EscrowSnapshot(
                command.auctionId(),
                command.sellerId(),
                command.itemRef(),
                command.currency(),
                EscrowStatus.OPEN,
                List.of(),
                Optional.empty(),
                command.openedAt());
    }

    EscrowSnapshot withHold(PlaceHold command) {
        if (status != EscrowStatus.OPEN) {
            throw new IllegalStateException("terminal escrow cannot accept holds");
        }
        if (!currency.equals(command.currency())) {
            throw new IllegalArgumentException("hold currency must match escrow currency");
        }
        if (sellerId.equals(command.bidderId())) {
            throw new IllegalArgumentException("seller cannot place hold on own escrow");
        }
        List<EscrowHold> nextHolds = new java.util.ArrayList<>(holds);
        nextHolds.add(new EscrowHold(holds.size() + 1L, command.bidderId(), command.amountMinor(), command.currency(), command.heldAt()));
        return new EscrowSnapshot(auctionId, sellerId, itemRef, currency, EscrowStatus.OPEN, nextHolds, Optional.empty(), command.heldAt());
    }

    EscrowSnapshot settle(SettleEscrow command) {
        requireOpen(command.auctionId());
        if (holds.isEmpty()) {
            throw new IllegalStateException("cannot settle escrow without holds");
        }
        ReleasePlan plan = ReleasePlan.settle(this);
        return terminal(EscrowStatus.SETTLED, plan, command.settledAt());
    }

    EscrowSnapshot cancel(CancelEscrow command) {
        requireOpen(command.auctionId());
        ReleasePlan plan = ReleasePlan.cancel(this);
        return terminal(EscrowStatus.CANCELLED, plan, command.cancelledAt());
    }

    String wireValue(long revision) {
        return "auctionId=" + auctionId
                + "|sellerId=" + sellerId
                + "|itemRef=" + itemRef
                + "|currency=" + currency
                + "|status=" + status
                + "|revision=" + revision
                + "|holds=" + holds.stream().map(EscrowHold::wireValue).collect(Collectors.joining(","))
                + "|releasePlan=" + releasePlan.map(ReleasePlan::wireValue).orElse("none");
    }

    private void requireOpen(String commandAuctionId) {
        if (!auctionId.equals(commandAuctionId)) {
            throw new IllegalArgumentException("command auction must match aggregate auction");
        }
        if (status != EscrowStatus.OPEN) {
            throw new IllegalStateException("terminal escrow cannot be mutated");
        }
    }

    private EscrowSnapshot terminal(EscrowStatus terminalStatus, ReleasePlan plan, Instant updatedAt) {
        return new EscrowSnapshot(auctionId, sellerId, itemRef, currency, terminalStatus, holds, Optional.of(plan), updatedAt);
    }
}
