package sh.harold.fulcrum.validation.auctionescrow;

import java.util.Objects;
import java.util.Optional;

public record AuctionEscrowState(Optional<EscrowSnapshot> current) {
    public AuctionEscrowState {
        current = current == null ? Optional.empty() : current;
    }

    static AuctionEscrowState empty() {
        return new AuctionEscrowState(Optional.empty());
    }

    AuctionEscrowState with(EscrowSnapshot snapshot) {
        return new AuctionEscrowState(Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    String wireValue(long revision) {
        return current.map(snapshot -> snapshot.wireValue(revision)).orElse("empty|revision=" + revision);
    }
}
